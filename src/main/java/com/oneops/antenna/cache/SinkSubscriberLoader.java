/*
* Copyright 2013-2014 @WalmartLabs.
*
*/
package com.oneops.antenna.cache;


import com.oneops.antenna.domain.*;
import com.oneops.antenna.domain.filter.NotificationFilter;
import com.oneops.antenna.domain.transform.Transformer;
import com.oneops.antenna.service.Dispatcher;
import com.oneops.cms.cm.domain.CmsCI;
import com.oneops.cms.cm.domain.CmsCIRelation;
import com.oneops.cms.cm.service.CmsCmProcessor;
import com.oneops.cms.crypto.CmsCrypto;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.google.common.cache.CacheLoader;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

/**
 * A subscriber list {@link com.google.common.cache.CacheLoader} for automatic loading of
 * sink subscribers when eviction happens (size-based eviction, time-based eviction,
 * or reference-based eviction depending on the Cache type).
 *
 * @author <a href="mailto:sgopal1@walmartlabs.com">Suresh G</a>
 * @version 1.0
 */
@Component
public class SinkSubscriberLoader extends CacheLoader<SinkKey, List<BasicSubscriber>> {

    /**
     * Logger instance
     */
    private static Logger logger = Logger.getLogger(SinkSubscriberLoader.class);

    /**
     * Cms CI processor
     */
    @Autowired
    private CmsCmProcessor cmProcessor;

    /**
     * Crypto util
     */
    @Qualifier("cmsCrypto")
    @Autowired
    private CmsCrypto cmsCrypto;

    /**
     * Default notification subscriber
     */
    @Autowired
    private URLSubscriber defaultSystemSubscriber;

    @Override
    public List<BasicSubscriber> load(SinkKey key) {

        logger.warn("Loading subscribers from cms for " + key);
        // Get nspath from sinkkey
        String nsPath = key.getNsPath();

        List<BasicSubscriber> subs = new ArrayList<BasicSubscriber>();
        // Add default system subscriber
        subs.add(defaultSystemSubscriber);

        if (!key.hasValidNsPath()) {
            //This is probably a cloud service
            logger.error("Couldn't get env for nsPath - " + nsPath);
            return subs;
        }

        CmsCI env = cmProcessor.getEnvByNS(nsPath);
        if (env == null) {
            logger.error("Can not get env for nsPath - " + nsPath);
            return subs;
        }

        List<CmsCI> sinks = getSubscribersForEnv(env);
        for (CmsCI sink : sinks) {
            try {
                decryptCI(sink);
                BasicSubscriber sub = null;

                if (sink.getCiClassName().equals("account.notification.sns.Sink")) {
                    sub = buildSnsSub(sink);
                } else if (sink.getCiClassName().equals("account.notification.url.Sink")) {
                    sub = buildUrlSub(sink);
                } else if (sink.getCiClassName().equals("account.notification.email.Sink")) {
                    sub = buildEmailSub(sink);
                } else if (sink.getCiClassName().equals("account.notification.jabber.Sink")) {
                    sub = buildJabberSub(sink);
                } else {
                    logger.error("Couldn't build notification sink for " + sink.getCiClassName());
                }
                if (sub != null) {
                    // Set the name
                    sub.setName(sink.getCiName());
                    // Add notification filter
                    sub.setFilter(NotificationFilter.fromSinkCI(sink));
                    // Add message transformer
                    sub.setTransformer(Transformer.fromSinkCI(sink));
                    // Add dispatching method
                    sub.setDispatchMethod(Dispatcher.Method.SYNC);
                    // Finally, add the subscriber to the list
                    subs.add(sub);
                }
                logger.info("Built the notification sink : " + sub.getName() + " for " + sink.getCiClassName());
            } catch (GeneralSecurityException e) {
                logger.error("Can not get decrypt ci - " + sink.getCiId() + " " + sink.getCiName() + ";", e);
            }
        }

        return subs;
    }

    /**
     * Jabber subscriber builder from sink CI
     *
     * @param sink sink object
     * @return jabber subscriber
     */
    private XMPPSubscriber buildJabberSub(CmsCI sink) {
        XMPPSubscriber xmppSink = new XMPPSubscriber();
        xmppSink.setChatRoom(sink.getAttribute("chat_room").getDfValue());
        xmppSink.setChatServer(sink.getAttribute("chat_server").getDfValue());
        xmppSink.setChatConference(sink.getAttribute("chat_conference").getDfValue());
        xmppSink.setChatPassword(sink.getAttribute("chat_password").getDfValue());
        xmppSink.setChatPort(Integer.valueOf(sink.getAttribute("chat_port").getDfValue()));
        xmppSink.setChatUser(sink.getAttribute("chat_user").getDfValue());
        return xmppSink;
    }

    /**
     * Email subscriber builder from sink CI
     *
     * @param sink sink object
     * @return email subscriber
     */
    private EmailSubscriber buildEmailSub(CmsCI sink) {
        EmailSubscriber eSub = new EmailSubscriber();
        eSub.setEmail(sink.getAttribute("email").getDfValue());
        return eSub;
    }

    /**
     * SNS subscriber builder from sink CI
     *
     * @param sink sink object
     * @return sns subscriber
     */
    private SNSSubscriber buildSnsSub(CmsCI sink) {
        SNSSubscriber snsSink = new SNSSubscriber();
        snsSink.setAwsAccessKey(sink.getAttribute("access").getDfValue());
        snsSink.setAwsSecretKey(sink.getAttribute("secret").getDfValue());
        return snsSink;
    }

    /**
     * Url subscriber builder from sink CI
     *
     * @param sink CI object
     * @return Url subscriber
     */
    private URLSubscriber buildUrlSub(CmsCI sink) {
        URLSubscriber urlSink = new URLSubscriber();
        urlSink.setUrl(sink.getAttribute("service_url").getDfValue());
        urlSink.setUserName(sink.getAttribute("user").getDfValue());
        urlSink.setPassword(sink.getAttribute("password").getDfValue());
        return urlSink;
    }

    /**
     * Get all sink subscribers for the given CmsCI environment.
     *
     * @param env Environment CI
     * @return List of subscribers defined in the specific env CI
     */
    private List<CmsCI> getSubscribersForEnv(CmsCI env) {
        List<CmsCI> subs = new ArrayList<CmsCI>();
        List<CmsCIRelation> assemblys = cmProcessor.getToCIRelationsNaked(env.getCiId(),
                "base.RealizedIn", "account.Assembly");
        if (assemblys.size() > 0) {
            List<CmsCIRelation> orgs = cmProcessor.getToCIRelationsNaked(assemblys.get(0).getFromCiId(),
                    "base.Manages", "account.Organization");
            if (orgs.size() > 0) {
                List<CmsCIRelation> subRels = cmProcessor.getFromCIRelations(orgs.get(0).getFromCiId(),
                        "base.ForwardsTo", null);
                for (CmsCIRelation subRel : subRels) {
                    subs.add(subRel.getToCi());
                }
            } else {
                logger.error("Can not get organization for env with id - " + env.getCiId());
            }
        } else {
            logger.error("Can not get assembly for env with id - " + env.getCiId());
        }

        return subs;
    }

    /**
     * Decrypt the CmsCI encrypted attributes.
     *
     * @param ci CmsCI
     * @throws GeneralSecurityException
     */
    private void decryptCI(CmsCI ci) throws GeneralSecurityException {
        for (String attrName : ci.getAttributes().keySet()) {
            String val = ci.getAttribute(attrName).getDfValue();
            if (val != null && val.startsWith(CmsCrypto.ENC_PREFIX)) {
                ci.getAttribute(attrName).setDfValue(cmsCrypto.decrypt(val));
            }
        }
    }


}
