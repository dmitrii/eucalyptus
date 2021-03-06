/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/
package com.eucalyptus.cloudformation.resources.standard.actions;


import com.eucalyptus.cloudformation.ValidationErrorException;
import com.eucalyptus.cloudformation.resources.ResourceAction;
import com.eucalyptus.cloudformation.resources.ResourceInfo;
import com.eucalyptus.cloudformation.resources.ResourceProperties;
import com.eucalyptus.cloudformation.resources.standard.info.AWSEC2SecurityGroupEgressResourceInfo;
import com.eucalyptus.cloudformation.resources.standard.propertytypes.AWSEC2SecurityGroupEgressProperties;
import com.eucalyptus.cloudformation.template.JsonHelper;
import com.eucalyptus.cloudformation.util.MessageHelper;
import com.eucalyptus.cloudformation.workflow.steps.Step;
import com.eucalyptus.cloudformation.workflow.steps.StepBasedResourceAction;
import com.eucalyptus.cloudformation.workflow.updateinfo.UpdateType;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.Topology;
import com.eucalyptus.compute.common.AuthorizeSecurityGroupEgressResponseType;
import com.eucalyptus.compute.common.AuthorizeSecurityGroupEgressType;
import com.eucalyptus.compute.common.Compute;
import com.eucalyptus.compute.common.DescribeSecurityGroupsResponseType;
import com.eucalyptus.compute.common.DescribeSecurityGroupsType;
import com.eucalyptus.compute.common.Filter;
import com.eucalyptus.compute.common.IpPermissionType;
import com.eucalyptus.compute.common.RevokeSecurityGroupEgressResponseType;
import com.eucalyptus.compute.common.RevokeSecurityGroupEgressType;
import com.eucalyptus.compute.common.SecurityGroupItemType;
import com.eucalyptus.compute.common.UserIdGroupPairType;
import com.eucalyptus.util.async.AsyncRequests;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Created by ethomas on 2/3/14.
 */
public class AWSEC2SecurityGroupEgressResourceAction extends StepBasedResourceAction {

  private AWSEC2SecurityGroupEgressProperties properties = new AWSEC2SecurityGroupEgressProperties();
  private AWSEC2SecurityGroupEgressResourceInfo info = new AWSEC2SecurityGroupEgressResourceInfo();

  public AWSEC2SecurityGroupEgressResourceAction() {
    // only replacement update options
    super(fromEnum(CreateSteps.class), fromEnum(DeleteSteps.class), null, null);
  }

  @Override
  public UpdateType getUpdateType(ResourceAction resourceAction, boolean stackTagsChanged) {
    UpdateType updateType = info.supportsTags() && stackTagsChanged ? UpdateType.NO_INTERRUPTION : UpdateType.NONE;
    AWSEC2SecurityGroupEgressResourceAction otherAction = (AWSEC2SecurityGroupEgressResourceAction) resourceAction;

    if (!Objects.equals(properties.getGroupId(), otherAction.properties.getGroupId())) {
      updateType = UpdateType.max(updateType, UpdateType.NEEDS_REPLACEMENT);
    }

    if (!Objects.equals(properties.getIpProtocol(), otherAction.properties.getIpProtocol())) {
      updateType = UpdateType.max(updateType, UpdateType.NEEDS_REPLACEMENT);
    }

    if (!Objects.equals(properties.getCidrIp(), otherAction.properties.getCidrIp())) {
      updateType = UpdateType.max(updateType, UpdateType.NEEDS_REPLACEMENT);
    }

    if (!Objects.equals(properties.getDestinationSecurityGroupId(), otherAction.properties.getDestinationSecurityGroupId())) {
      updateType = UpdateType.max(updateType, UpdateType.NEEDS_REPLACEMENT);
    }

    if (!Objects.equals(properties.getFromPort(), otherAction.properties.getFromPort())) {
      updateType = UpdateType.max(updateType, UpdateType.NEEDS_REPLACEMENT);
    }

    if (!Objects.equals(properties.getToPort(), otherAction.properties.getToPort())) {
      updateType = UpdateType.max(updateType, UpdateType.NEEDS_REPLACEMENT);
    }
    return updateType;
  }


  private enum CreateSteps implements Step {
    CREATE_EGRESS_RULE {
      @Override
      public ResourceAction perform(ResourceAction resourceAction) throws Exception {
        AWSEC2SecurityGroupEgressResourceAction action = (AWSEC2SecurityGroupEgressResourceAction) resourceAction;
        ServiceConfiguration configuration = Topology.lookup(Compute.class);
        action.validateProperties();
        // Make sure security group exists.
        DescribeSecurityGroupsType describeSecurityGroupsType = MessageHelper.createMessage(DescribeSecurityGroupsType.class, action.info.getEffectiveUserId());
        describeSecurityGroupsType.setFilterSet( Lists.newArrayList( Filter.filter( "group-id", action.properties.getGroupId( ) ) ) );
        DescribeSecurityGroupsResponseType describeSecurityGroupsResponseType = AsyncRequests.sendSync(configuration, describeSecurityGroupsType);
        ArrayList<SecurityGroupItemType> securityGroupItemTypeArrayList = describeSecurityGroupsResponseType.getSecurityGroupInfo();
        boolean hasDefaultEgressRule = false;
        if (securityGroupItemTypeArrayList == null || securityGroupItemTypeArrayList.isEmpty()) {
          throw new ValidationErrorException("No such group with id '" + action.properties.getGroupId()+"'");
        } else {
          // should only be one, so take the first one
          SecurityGroupItemType securityGroupItemType = securityGroupItemTypeArrayList.get(0);
          if (securityGroupItemType.getIpPermissionsEgress() != null) {
            for (IpPermissionType ipPermissionType : securityGroupItemType.getIpPermissionsEgress()) {
              if (action.isDefaultEgressRule(ipPermissionType)) {
                hasDefaultEgressRule = true;
                break;
              }
            }
          }
        }

        AuthorizeSecurityGroupEgressType authorizeSecurityGroupEgressType = MessageHelper.createMessage(AuthorizeSecurityGroupEgressType.class, action.info.getEffectiveUserId());
        authorizeSecurityGroupEgressType.setGroupId(action.properties.getGroupId());
        IpPermissionType ipPermissionType = new IpPermissionType(
          action.properties.getIpProtocol(),
          action.properties.getFromPort(),
          action.properties.getToPort()
        );
        if (!Strings.isNullOrEmpty(action.properties.getCidrIp())) {
          ipPermissionType.setCidrIpRanges(Lists.newArrayList(action.properties.getCidrIp()));
        }
        if (!Strings.isNullOrEmpty(action.properties.getDestinationSecurityGroupId())) {
          ipPermissionType.setGroups(Lists.newArrayList(new UserIdGroupPairType(null, null, action.properties.getDestinationSecurityGroupId())));
        }
        authorizeSecurityGroupEgressType.setIpPermissions(Lists.newArrayList(ipPermissionType));
        AuthorizeSecurityGroupEgressResponseType authorizeSecurityGroupIngressResponseType = AsyncRequests.<AuthorizeSecurityGroupEgressType, AuthorizeSecurityGroupEgressResponseType> sendSync(configuration, authorizeSecurityGroupEgressType);

        // remove default (if there)
        if (hasDefaultEgressRule) {
          RevokeSecurityGroupEgressType revokeSecurityGroupEgressType = MessageHelper.createMessage(RevokeSecurityGroupEgressType.class, action.info.getEffectiveUserId());
          revokeSecurityGroupEgressType.setGroupId(action.properties.getGroupId());
          revokeSecurityGroupEgressType.setIpPermissions(Lists.newArrayList(DEFAULT_EGRESS_RULE()));
          RevokeSecurityGroupEgressResponseType revokeSecurityGroupEgressResponseType = AsyncRequests.<RevokeSecurityGroupEgressType, RevokeSecurityGroupEgressResponseType> sendSync(configuration, revokeSecurityGroupEgressType);
        }

        action.info.setPhysicalResourceId(action.getDefaultPhysicalResourceId());
        action.info.setCreatedEnoughToDelete(true);
        action.info.setReferenceValueJson(JsonHelper.getStringFromJsonNode(new TextNode(action.info.getPhysicalResourceId())));
        return action;
      }
    };

    @Nullable
    @Override
    public Integer getTimeout() {
      return null;
    }
  }

  private enum DeleteSteps implements Step {
    DELETE_EGRESS_RULE {
      @Override
      public ResourceAction perform(ResourceAction resourceAction) throws Exception {
        AWSEC2SecurityGroupEgressResourceAction action = (AWSEC2SecurityGroupEgressResourceAction) resourceAction;
        ServiceConfiguration configuration = Topology.lookup(Compute.class);
        if (action.info.getCreatedEnoughToDelete() != Boolean.TRUE) return action;

        // property validation
        action.validateProperties();
        // Make sure security group exists.
        DescribeSecurityGroupsType describeSecurityGroupsType = MessageHelper.createMessage(DescribeSecurityGroupsType.class, action.info.getEffectiveUserId());
        describeSecurityGroupsType.setFilterSet( Lists.newArrayList( Filter.filter( "group-id", action.properties.getGroupId( ) ) ) );
        DescribeSecurityGroupsResponseType describeSecurityGroupsResponseType = AsyncRequests.sendSync(configuration, describeSecurityGroupsType);
        ArrayList<SecurityGroupItemType> securityGroupItemTypeArrayList = describeSecurityGroupsResponseType.getSecurityGroupInfo();
        if (securityGroupItemTypeArrayList == null || securityGroupItemTypeArrayList.isEmpty()) {
          return action; // no group
        }

        RevokeSecurityGroupEgressType revokeSecurityGroupEgressType = MessageHelper.createMessage(RevokeSecurityGroupEgressType.class, action.info.getEffectiveUserId());
        revokeSecurityGroupEgressType.setGroupId(action.properties.getGroupId());
        IpPermissionType ipPermissionType = new IpPermissionType(
          action.properties.getIpProtocol(),
          action.properties.getFromPort(),
          action.properties.getToPort()
        );
        if (!Strings.isNullOrEmpty(action.properties.getCidrIp())) {
          ipPermissionType.setCidrIpRanges(Lists.newArrayList(action.properties.getCidrIp()));
        }
        if (!Strings.isNullOrEmpty(action.properties.getDestinationSecurityGroupId())) {
          // Generally no need for DestinationSecurityGroupOwnerId if DestinationSecurityGroupId is set, but pass it along if set
          ipPermissionType.setGroups(Lists.newArrayList(new UserIdGroupPairType(null, null, action.properties.getDestinationSecurityGroupId())));
        }
        revokeSecurityGroupEgressType.setIpPermissions(Lists.newArrayList(ipPermissionType));
        RevokeSecurityGroupEgressResponseType revokeSecurityGroupEgressResponseType = AsyncRequests.<RevokeSecurityGroupEgressType, RevokeSecurityGroupEgressResponseType> sendSync(configuration, revokeSecurityGroupEgressType);
        // do one last check, if there and no egress rules, re-add default rule
        DescribeSecurityGroupsType describeSecurityGroupsType2 = MessageHelper.createMessage(DescribeSecurityGroupsType.class, action.info.getEffectiveUserId());
        describeSecurityGroupsType2.setFilterSet( Lists.newArrayList( Filter.filter( "group-id", action.properties.getGroupId( ) ) ) );
        DescribeSecurityGroupsResponseType describeSecurityGroupsResponseType2 = AsyncRequests.sendSync(configuration, describeSecurityGroupsType);
        ArrayList<SecurityGroupItemType> securityGroupItemTypeArrayList2 = describeSecurityGroupsResponseType2.getSecurityGroupInfo();
        if (securityGroupItemTypeArrayList2 == null || securityGroupItemTypeArrayList2.isEmpty()) {
          return action; // no group
        }
        if (securityGroupItemTypeArrayList2.get(0).getIpPermissionsEgress() == null || securityGroupItemTypeArrayList2.get(0).getIpPermissionsEgress().isEmpty()) {
          AuthorizeSecurityGroupEgressType authorizeSecurityGroupEgressType = MessageHelper.createMessage(AuthorizeSecurityGroupEgressType.class, action.info.getEffectiveUserId());
          authorizeSecurityGroupEgressType.setGroupId(action.properties.getGroupId());
          authorizeSecurityGroupEgressType.setIpPermissions(Lists.newArrayList(DEFAULT_EGRESS_RULE()));
          AuthorizeSecurityGroupEgressResponseType authorizeSecurityGroupIngressResponseType = AsyncRequests.<AuthorizeSecurityGroupEgressType, AuthorizeSecurityGroupEgressResponseType> sendSync(configuration, authorizeSecurityGroupEgressType);
        }
        return action;
      }
    };

    @Nullable
    @Override
    public Integer getTimeout() {
      return null;
    }
  }

  @Override
  public ResourceProperties getResourceProperties() {
    return properties;
  }

  @Override
  public void setResourceProperties(ResourceProperties resourceProperties) {
    properties = (AWSEC2SecurityGroupEgressProperties) resourceProperties;
  }

  @Override
  public ResourceInfo getResourceInfo() {
    return info;
  }

  @Override
  public void setResourceInfo(ResourceInfo resourceInfo) {
    info = (AWSEC2SecurityGroupEgressResourceInfo) resourceInfo;
  }

  private static final IpPermissionType DEFAULT_EGRESS_RULE() {
    IpPermissionType ipPermissionType = new IpPermissionType();
    ipPermissionType.setIpProtocol("-1");
    ipPermissionType.setCidrIpRanges(Lists.newArrayList("0.0.0.0/0"));
    return ipPermissionType;
  }

  private boolean isDefaultEgressRule(IpPermissionType ipPermissionType) {
    return ipPermissionType.getIpProtocol().equals("-1") && ipPermissionType.getFromPort() == null
      && ipPermissionType.getToPort() == null && ipPermissionType.getCidrIpRanges() != null &&
      ipPermissionType.getCidrIpRanges().size() == 1 && ipPermissionType.getCidrIpRanges().get(0).equals("0.0.0.0/0");
  }

  private void validateProperties() throws ValidationErrorException {
    // Can't specify cidr and destination security group
    if (!Strings.isNullOrEmpty(properties.getCidrIp()) && !Strings.isNullOrEmpty(properties.getDestinationSecurityGroupId())) {
      throw new ValidationErrorException("Both CidrIp and DestinationSecurityGroup cannot be specified");
    }
  }



}


