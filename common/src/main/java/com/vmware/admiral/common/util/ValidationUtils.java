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

package com.vmware.admiral.common.util;

import static com.vmware.admiral.common.util.AssertUtil.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import com.vmware.xenon.common.Operation;

public class ValidationUtils {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]+");

    public static void validateContainerName(String name) {
        if ((name != null) && !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid container name '" + name + "', only "
                    + NAME_PATTERN.pattern() + " are allowed.");
        }
    }

    public static void validatePort(String port) {
        int portNumber = Integer.parseInt(port);
        assertTrue(0 <= portNumber && portNumber <= 65535,
                String.format("'%s' is not a valid port number.", port));
    }

    public static void validateHost(String host) {
        try {
            if (host.equals(new URI("http://" + host + ":80").getHost())) {
                return;
            }
        } catch (URISyntaxException e) {
        }
        throw new IllegalArgumentException("Host [" + host + "] is not valid.");
    }

    public static boolean validate(Operation op, ValidateOperationHandler validateHandler) {
        try {
            validateHandler.validate();
            return true;
        } catch (Exception e) {
            handleValidationException(op, e);
            return false;
        }
    }

    public static void handleValidationException(Operation op, Throwable e) {
        Throwable ex = e;
        if (!(e instanceof IllegalArgumentException)) {
            ex = new IllegalArgumentException(e.getMessage());
        }
        op.setStatusCode(Operation.STATUS_CODE_BAD_REQUEST);
        // with body = null fail(ex) will populate it with the proper ServiceErrorResponse content
        op.setBody(null);
        op.fail(ex);
    }

    @FunctionalInterface
    public static interface ValidateOperationHandler {
        void validate() throws Exception;
    }
}
