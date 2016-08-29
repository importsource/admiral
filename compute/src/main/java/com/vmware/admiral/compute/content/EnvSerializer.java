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

package com.vmware.admiral.compute.content;

/**
 * Serialize env variable to map
 */
public class EnvSerializer extends SplitStringToMapSerializer {
    private static final long serialVersionUID = 1L;

    private static final String SPLIT_REGEX = "=";
    private static final String[] ARG_NAMES = { "var", "value" };

    protected EnvSerializer() {
        super(SPLIT_REGEX, ARG_NAMES);
    }
}
