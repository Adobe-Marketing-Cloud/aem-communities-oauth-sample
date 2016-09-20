/*************************************************************************************************
 * 
 *  ADOBE SYSTEMS INCORPORATED
 *   Copyright 2016 Adobe Systems Incorporated
 *   All Rights Reserved.
 *
 *  NOTICE:  Adobe permits you to use, modify, and distribute this file in accordance with the 
 *  terms of the Adobe license agreement accompanying it.  If you have received this file from a 
 *  source other than Adobe, then your use, modification, or distribution of it requires the prior 
 *  written permission of Adobe.
 *
 ************************************************************************************************/
package com.adobe.social.sample.oauth.impl;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map.Entry;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;

import org.apache.commons.lang.StringUtils;
import org.apache.sling.jcr.resource.JcrPropertyMap;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.text.Text;

/**
 * Event Listener for propagating sling:OsgiConfig when cloud service configs are edited.
 */
public class LinkedinCloudConfigEventListener implements EventListener {
    private static final String OAUTH_CONFIG_ID = "oauth.config.id";
    private static final String OAUTH = "oauth";
    private static final String FACTORY_PID = "com.adobe.granite.auth.oauth.provider";
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final Session session;
    private final String configRoot;
    private final ConfigurationAdmin configAdmin;


    LinkedinCloudConfigEventListener(final Session session, final String configRoot, final ConfigurationAdmin configAdmin) {
        this.session = session;
        this.configRoot = configRoot;
        this.configAdmin = configAdmin;
    }

    /**
     * Remove all existing configs with the specified oauth.config.id.
     *
     */
    private void purgeExistingConfigurations(final JcrPropertyMap newConfigProps) {
        if (newConfigProps.containsKey(OAUTH_CONFIG_ID)) {
            final String configId = String.valueOf(newConfigProps.get(OAUTH_CONFIG_ID));
            if (log.isDebugEnabled()) {
                log.debug("purgeExistingConfigurations, configId: {}", configId);
            }
            if (StringUtils.isNotEmpty(configId)) {
                final String query =
                    String.format("(&(service.factoryPid=%s)(oauth.config.id=%s))", FACTORY_PID, configId);
                if (log.isDebugEnabled()) {
                    log.debug("purgeExistingConfigurations, search query: {}", query);
                }
                try {
                    final Configuration[] existingConfigurations = configAdmin.listConfigurations(query);
                    if (existingConfigurations != null && existingConfigurations.length > 0) {
                        if (log.isDebugEnabled()) {
                            log.debug("purgeExistingConfigurations, found {} configurations",
                                existingConfigurations.length);
                        }
                        for (int i = 0; i < existingConfigurations.length; i++) {
                            final Configuration configuration = existingConfigurations[i];
                            if (log.isDebugEnabled()) {
                                log.debug("purgeExistingConfigurations, deleted pid {}", configuration.getPid());
                            }
                            configuration.delete();
                        }
                    }
                }
                // We don't want to completely fail because we can't remove a configuration.
                catch (final IOException e) {
                    log.error("purgeExistingConfigurations exception {}", e);
                } catch (InvalidSyntaxException e) {
                    log.error("purgeExistingConfigurations exception {}", e);
                }
            }
        }
    }

    @Override
    public void onEvent(final EventIterator events) {
        // copy the config to providerConfigRoot
        while (events.hasNext()) {
            final Event event = events.nextEvent();
            try {
                final String path = event.getPath();
                final String configNodePath = configRoot + "/" + Text.getName(path);
                if (log.isDebugEnabled()) {
                    log.debug("change event {}, path: {}", event.getType(), path);
                }

                try {
                    final Configuration targetConfig = configAdmin.createFactoryConfiguration(FACTORY_PID, null);
                    if (log.isDebugEnabled()) {
                        log.debug("New configuration {} created", targetConfig.getPid());
                    }
                    final Node node = session.getNode(path);
                    if (node != null) {
                        final Dictionary<String, Object> properties = new Hashtable<String, Object>();
                        final JcrPropertyMap props = new JcrPropertyMap(node);
                        purgeExistingConfigurations(props);
                        for (final Entry<String, Object> entry : props.entrySet()) {
                            final String key = entry.getKey();
                            if (StringUtils.contains(key, OAUTH)) {
                                properties.put(key, entry.getValue());
                                if (log.isDebugEnabled()) {
                                    log.debug("Setting attribte {} to {}", key, entry.getValue());
                                }
                            }
                        }
                        targetConfig.update(properties);
                    }
                    session.save();
                    if (log.isDebugEnabled()) {
                        log.debug("Committed session {}", targetConfig.getPid());
                    }
                } catch (final IOException e) {
                    log.error("Error while creating config at {}", configNodePath);
                }
            } catch (final RepositoryException e) {
                log.error("Error while handling event '" + event.getType() + "'", e);
            }
        }
    }
}
