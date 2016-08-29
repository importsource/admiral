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

package com.vmware.admiral.test.integration.compute.aws;

import com.vmware.admiral.compute.endpoint.EndpointService.EndpointState;
import com.vmware.admiral.test.integration.compute.BaseComputeProvisionIT;
import com.vmware.photon.controller.model.adapters.awsadapter.AWSConstants;
import com.vmware.photon.controller.model.resources.ComputeDescriptionService.ComputeDescription;

public class AwsComputeProvisionIT extends BaseComputeProvisionIT {

    private static final String SECURITY_GROUP_PROP = "test.aws.security.group";
    private static final String ACCESS_KEY_PROP = "test.aws.access.key";
    private static final String ACCESS_SECRET_PROP = "test.aws.secret.key";
    private static final String REGION_ID_PROP = "test.aws.region.id";

    @Override
    protected EndpointType getEndpointType() {
        return EndpointType.aws;
    }

    @Override
    protected void extendEndpoint(EndpointState endpoint) {
        endpoint.privateKeyId = getTestRequiredProp(ACCESS_KEY_PROP);
        endpoint.privateKey = getTestRequiredProp(ACCESS_SECRET_PROP);
        endpoint.regionId = getTestProp(REGION_ID_PROP, "us-east-1");
    }

    @Override
    protected void extendComputeDescription(ComputeDescription computeDescription)
            throws Exception {
        String securityGroup = getTestProp(SECURITY_GROUP_PROP);
        if (securityGroup != null) {
            computeDescription.customProperties.put(AWSConstants.AWS_SECURITY_GROUP, securityGroup);
        }
    }

}
