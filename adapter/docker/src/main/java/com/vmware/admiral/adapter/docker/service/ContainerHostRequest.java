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

package com.vmware.admiral.adapter.docker.service;

import com.vmware.admiral.adapter.common.AdapterRequest;
import com.vmware.admiral.adapter.common.ContainerHostOperationType;

/**
 * Container Host operations request.
 */
public class ContainerHostRequest extends AdapterRequest {

    @Override
    public void validate() {
        super.validate();
        if (operationTypeId != null) {
            if (ContainerHostOperationType.instanceById(operationTypeId) == null) {
                throw new IllegalArgumentException("Not valid host operationTypeId: "
                        + operationTypeId);
            }
        }
    }

    public ContainerHostOperationType getOperationType() {
        return ContainerHostOperationType.instanceById(operationTypeId);
    }
}
