/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.service.common;

import static com.vmware.admiral.common.util.AssertUtil.assertNotEmpty;

import java.util.Enumeration;
import java.util.LinkedList;
import java.util.Properties;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.FileUtil;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;

/**
 * The Configuration Service is utility service providing generic key/value configuration
 * capabilities.
 */
public class ConfigurationService extends StatefulService {
    public static final String DEFAULT_CONFIGURATION_PROPERTIES_FILE_NAME = "/config.properties";
    public static final String CUSTOM_CONFIGURATION_PROPERTIES_FILE_NAMES = System
            .getProperty("configuration.properties");
    public static final String NO_OVERRIDE_PREFIX_MARKER_FOR_PROPERTIES = "__";

    public static class ConfigurationState extends com.vmware.xenon.common.ServiceDocument {
        /** (Required) The name used as a key for a given property value */
        @Documentation(description = "The name used as a key for a given property value.")
        public String key;

        /** (Required) The value for a given key. */
        @Documentation(description = "The value for a given key.")
        public String value;
    }

    public static class ConfigurationFactoryService extends FactoryService {
        public static final String SELF_LINK = ManagementUriParts.CONFIG_PROPS;

        public ConfigurationFactoryService() {
            super(ConfigurationState.class);
            super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
        }

        @Override
        public Service createServiceInstance() {
            return new ConfigurationService();
        }
    }

    public ConfigurationService() {
        super(ConfigurationState.class);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
    }

    @Override
    public void handleCreate(Operation post) {
        if (!checkForBody(post)) {
            return;
        }

        ConfigurationState state = post.getBody(ConfigurationState.class);
        validate(state);

        post.complete();
    }

    @Override
    public void handlePut(Operation put) {
        if (!checkForBody(put)) {
            return;
        }

        ConfigurationState body = put.getBody(ConfigurationState.class);
        validate(body);

        this.setState(put, body);
        put.setBody(body).complete();
    }

    private void validate(ConfigurationState state) {
        assertNotEmpty(state.key, "key");
        assertNotEmpty(state.value, "value");
    }

    public static ConfigurationState[] getConfigurationProperties() {

        Properties props = FileUtil.getProperties(DEFAULT_CONFIGURATION_PROPERTIES_FILE_NAME, true);

        // Override the default values with custom properties.
        // Properties starting with "core"are protected and cannot be overridden.
        if (CUSTOM_CONFIGURATION_PROPERTIES_FILE_NAMES != null
                && !CUSTOM_CONFIGURATION_PROPERTIES_FILE_NAMES.isEmpty()) {
            for (String fileName : CUSTOM_CONFIGURATION_PROPERTIES_FILE_NAMES.split(",")) {
                Properties customProps = FileUtil.getProperties(
                        fileName,
                        false);
                @SuppressWarnings("unchecked")
                Enumeration<String> customEnums = (Enumeration<String>) customProps.propertyNames();
                while (customEnums.hasMoreElements()) {
                    String customKey = customEnums.nextElement();
                    if (!customKey.startsWith(NO_OVERRIDE_PREFIX_MARKER_FOR_PROPERTIES)) {
                        props.put(customKey, customProps.getProperty(customKey));
                    }
                }
            }
        }

        LinkedList<ConfigurationState> ls = new LinkedList<ConfigurationState>();
        @SuppressWarnings("unchecked")
        Enumeration<String> enums = (Enumeration<String>) props.propertyNames();
        while (enums.hasMoreElements()) {
            String key = enums.nextElement();
            String value = props.getProperty(key);
            ConfigurationState configState = new ConfigurationState();
            configState.documentSelfLink = UriUtils
                    .buildUriPath(ConfigurationFactoryService.SELF_LINK, key);
            configState.key = key;
            configState.value = value;
            ls.add(configState);

        }
        return ls.toArray(new ConfigurationState[ls.size()]);
    }

}
