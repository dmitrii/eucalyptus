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
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.auth.euare;

import com.eucalyptus.auth.AuthContext;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TimeZone;

import org.apache.log4j.Logger;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.springframework.web.util.UriUtils;

import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.Permissions;
import com.eucalyptus.auth.PolicyParseException;
import com.eucalyptus.auth.ServerCertificate;
import com.eucalyptus.auth.euare.persist.entities.ServerCertificateEntity;
import com.eucalyptus.auth.euare.ldap.LdapSync;
import com.eucalyptus.auth.euare.persist.entities.UserEntity;
import com.eucalyptus.auth.euare.principal.EuareAccount;
import com.eucalyptus.auth.euare.principal.EuareGroup;
import com.eucalyptus.auth.euare.principal.EuareRole;
import com.eucalyptus.auth.euare.principal.EuareUser;
import com.eucalyptus.auth.policy.PolicySpec;
import com.eucalyptus.auth.policy.ern.EuareResourceName;
import com.eucalyptus.auth.policy.key.Iso8601DateParser;
import com.eucalyptus.auth.principal.AccessKey;
import com.eucalyptus.auth.principal.Certificate;
import com.eucalyptus.auth.euare.principal.EuareInstanceProfile;
import com.eucalyptus.auth.principal.Policy;
import com.eucalyptus.auth.principal.User;
import com.eucalyptus.auth.util.X509CertHelper;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.crypto.Certs;
import com.eucalyptus.crypto.util.B64;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionResource;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.RestrictedTypes;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;

@SuppressWarnings( "UnusedDeclaration" )
public class EuareService {
  
  private static final Logger LOG = Logger.getLogger( EuareService.class );

  private static final boolean ENCODE_POLICIES =
      Boolean.valueOf( System.getProperty( "com.eucalyptus.auth.euare.encodePolicies", "true" ) );
  
  public CreateAccountResponseType createAccount(CreateAccountType request) throws EucalyptusCloudException {
    CreateAccountResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    try {
      EuareAccount newAccount = Privileged.createAccount( requestUser, request.getAccountName( ), null/*password*/, null/*email*/ );
      AccountType account = reply.getCreateAccountResult( ).getAccount( );
      account.setAccountName( newAccount.getName( ) );
      account.setAccountId( newAccount.getAccountNumber( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create account by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.ACCOUNT_ALREADY_EXISTS.equals( e.getMessage( ) ) || AuthException.CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Account " + request.getAccountName( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid account name " + request.getAccountName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public DeleteAccountResponseType deleteAccount(DeleteAccountType request) throws EucalyptusCloudException {
    DeleteAccountResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount accountFound = lookupAccountByName( request.getAccountName( ) );
    try {
      boolean recursive = ( request.getRecursive( ) != null && request.getRecursive( ) );
      Privileged.deleteAccount( requestUser, accountFound, recursive );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete account by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.ACCOUNT_DELETE_CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DELETE_CONFLICT, "Account " + request.getAccountName( ) + " can not be deleted." );
        } else if ( AuthException.DELETE_SYSTEM_ACCOUNT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DELETE_CONFLICT, "System account can not be deleted." );
        } else if ( AuthException.EMPTY_ACCOUNT_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty account name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public ListAccountsResponseType listAccounts(ListAccountsType request) throws EucalyptusCloudException {
    ListAccountsResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    if ( !Permissions.perhapsAuthorized( PolicySpec.VENDOR_IAM, PolicySpec.IAM_LISTACCOUNTS, ctx.getAuthContext( ) ) ) {
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list accounts" );
    }
    ArrayList<AccountType> accounts = reply.getListAccountsResult( ).getAccounts( ).getMemberList( );
    try {
      for ( final EuareAccount account : Iterables.filter( com.eucalyptus.auth.euare.Accounts.listAllAccounts(), RestrictedTypes.filterPrivileged( ) ) ) {
        AccountType at = new AccountType( );
        at.setAccountName( account.getName( ) );
        at.setAccountId( account.getAccountNumber( ) );
        accounts.add( at );
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public ListGroupsResponseType listGroups(ListGroupsType request) throws EucalyptusCloudException {
    ListGroupsResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    String path = "/";
    if ( !Strings.isNullOrEmpty( request.getPathPrefix( ) ) ) {
      path = request.getPathPrefix( );
    }
    if ( !Permissions.perhapsAuthorized( PolicySpec.VENDOR_IAM, PolicySpec.IAM_LISTGROUPS, ctx.getAuthContext( ) ) ) {
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list groups" );
    }
    reply.getListGroupsResult( ).setIsTruncated( false );
    ArrayList<GroupType> groups = reply.getListGroupsResult( ).getGroups( ).getMemberList( );
    try {
      for ( final EuareGroup group : account.getGroups( ) ) {
        if ( group.getPath( ).startsWith( path ) ) {
          if ( Privileged.allowListGroup( requestUser, account, group ) ) {
            GroupType g = new GroupType( );
            fillGroupResult( g, group, account );
            groups.add( g );
          }
        }
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteAccessKeyResponseType deleteAccessKey(DeleteAccessKeyType request) throws EucalyptusCloudException {
    DeleteAccessKeyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      Privileged.deleteAccessKey( requestUser, account, userFound, request.getAccessKeyId( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete access key of " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_KEY_ID.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_ID, "Empty key id" );
        } else if ( AuthException.NO_SUCH_KEY.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Access Key with id "+request.getAccessKeyId( )+" not found for user" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListSigningCertificatesResponseType listSigningCertificates(ListSigningCertificatesType request) throws EucalyptusCloudException {
    ListSigningCertificatesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) || request.getDelegateAccount( ) != null ) {
      userFound = lookupUserByName( account, Objects.firstNonNull(
          Strings.emptyToNull( request.getUserName( ) ),
          userFound.getName( ) ) );
    }
    ListSigningCertificatesResultType result = reply.getListSigningCertificatesResult( );
    result.setIsTruncated( false );
    ArrayList<SigningCertificateType> certs = result.getCertificates( ).getMemberList( );
    try {
      for ( Certificate cert : Privileged.listSigningCertificates( requestUser, account, userFound ) ) {
        SigningCertificateType c = new SigningCertificateType( );
        c.setUserName( userFound.getName( ) );
        c.setCertificateId( cert.getCertificateId( ) );
        c.setCertificateBody( B64.url.decString( cert.getPem( ) ) );
        c.setStatus( cert.isActive( ) ? "Active" : "Inactive" );
        c.setUploadDate( cert.getCreateDate( ) );
        certs.add( c );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list signing certificates for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UploadSigningCertificateResponseType uploadSigningCertificate(UploadSigningCertificateType request) throws EucalyptusCloudException {
    UploadSigningCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      Certificate cert = Privileged.uploadSigningCertificate( requestUser, account, userFound, request.getCertificateBody( ) );
      SigningCertificateType result = reply.getUploadSigningCertificateResult( ).getCertificate( );
      result.setUserName( userFound.getName( ) );
      result.setCertificateId( cert.getCertificateId( ) );
      result.setCertificateBody( request.getCertificateBody( ) );
      result.setStatus( "Active" );
      result.setUploadDate( cert.getCreateDate( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to upload signing certificate of " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Signing certificate limit exceeded" );
        } else if ( AuthException.CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DUPLICATE_CERTIFICATE, "Trying to upload duplicate certificate" );          
        } else if ( AuthException.INVALID_CERT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_CERTIFICATE, "Invalid certificate " + request.getCertificateBody( ) );
        } else if ( AuthException.EMPTY_CERT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_CERTIFICATE, "Empty certificate body" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteUserPolicyResponseType deleteUserPolicy(DeleteUserPolicyType request) throws EucalyptusCloudException {
    DeleteUserPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    try {
      Privileged.deleteUserPolicy( requestUser, account, userFound, request.getPolicyName( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete policy for user " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }     
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public PutUserPolicyResponseType putUserPolicy(PutUserPolicyType request) throws EucalyptusCloudException {
    PutUserPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    try {
      Privileged.putUserPolicy( requestUser, account, userFound, request.getPolicyName( ), request.getPolicyDocument( ) );
    } catch ( PolicyParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_POLICY_DOCUMENT, "Error in uploaded policy: " + e.getMessage(), e );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to put user policy for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid policy name " + request.getPolicyName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListServerCertificatesResponseType listServerCertificates(ListServerCertificatesType request) throws EucalyptusCloudException {
    final ListServerCertificatesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    String pathPrefix = request.getPathPrefix();
    if(pathPrefix == null || pathPrefix.isEmpty())
      pathPrefix = "/";
    
    try{
      final List<ServerCertificate> certs = Privileged.listServerCertificate( requestUser, account, pathPrefix );
      final ListServerCertificatesResultType result = new ListServerCertificatesResultType();
      final ServerCertificateMetadataListTypeType lists = new ServerCertificateMetadataListTypeType();
      lists.setMemberList(new ArrayList<>(Collections2.transform(certs, new Function<ServerCertificate, ServerCertificateMetadataType>(){
        @Override
        public ServerCertificateMetadataType apply(ServerCertificate cert) {
          return getServerCertificateMetadata(cert);
        }
      })));
      result.setServerCertificateMetadataList(lists);
      reply.setListServerCertificatesResult(result);
    }catch(final AuthException ex){
      if ( AuthException.ACCESS_DENIED.equals( ex.getMessage( ) ) ) 
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list server certificates by " + ctx.getUser( ).getName( ) );
      else {
        LOG.error("failed to list server certificates", ex);
        throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
      }
    }catch(final Exception ex){
      LOG.error("failed to list server certificates", ex);
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE); 
    }
    reply.set_return(true);
    return reply;
  }

  public GetUserPolicyResponseType getUserPolicy(GetUserPolicyType request) throws EucalyptusCloudException {
    GetUserPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    try {
      Policy policy = Privileged.getUserPolicy( requestUser, account, userFound, request.getPolicyName( ) );
      if ( policy != null ) {
        GetUserPolicyResultType result = reply.getGetUserPolicyResult( );
        result.setUserName( request.getUserName( ) );
        result.setPolicyName( request.getPolicyName( ) );
        result.setPolicyDocument( encodePolicy( policy.getText( ) ) );
      } else {
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find policy " + request.getPolicyName( ) );
      }
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get user policies for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UpdateLoginProfileResponseType updateLoginProfile(UpdateLoginProfileType request) throws EucalyptusCloudException {
    UpdateLoginProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    try {
      Privileged.updateLoginProfile( requestUser, account, userFound, request.getPassword( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update login profile of " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.INVALID_PASSWORD.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, "Invalid password", "Invalid password" );
        } else if ( AuthException.NO_SUCH_LOGIN_PROFILE.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Cannot find Login Profile for User " + userFound.getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UpdateServerCertificateResponseType updateServerCertificate(UpdateServerCertificateType request) throws EucalyptusCloudException {
    final UpdateServerCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final String certName = request.getServerCertificateName();
    if(certName == null || certName.length()<=0)
      throw new EuareException(HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Certificate name is empty");
    
    try{
      final String newCertName = request.getNewServerCertificateName();
      final String newPath = request.getNewPath();
      if( (newCertName!=null&&newCertName.length()>0) || (newPath!=null&&newPath.length()>0))
        Privileged.updateServerCertificate( requestUser, account, certName, newCertName, newPath);
    }catch(final AuthException ex){
      if ( AuthException.ACCESS_DENIED.equals( ex.getMessage( ) ) ) 
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update server certificates by " + ctx.getUser( ).getName( ) );
      else if (AuthException.SERVER_CERT_NO_SUCH_ENTITY.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Server certificate "+certName+" does not exist");
      else if (AuthException.SERVER_CERT_ALREADY_EXISTS.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Server certificate "+ request.getNewServerCertificateName()+ " already exists.");
      else{
        LOG.error("failed to update server certificate", ex);
        throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
      }
    }catch(final Exception ex){
      LOG.error("failed to update server certificate", ex);
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE); 
    }
    
    reply.set_return(true);
    return reply;
  }

  public UpdateUserResponseType updateUser(UpdateUserType request) throws EucalyptusCloudException {
    UpdateUserResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    try {
      Boolean enabled = request.getEnabled( ) != null ? "true".equalsIgnoreCase( request.getEnabled( ) ) : null;
      Long passwordExpiration = request.getPasswordExpiration( ) != null ? Iso8601DateParser.parse( request.getPasswordExpiration( ) ).getTime( ) : null;
      Privileged.modifyUser( requestUser, account, userFound, request.getNewUserName( ), request.getNewPath( ), enabled, passwordExpiration, null/*info*/ );
    } catch ( ParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_VALUE, "Invalid password expiration " + request.getPasswordExpiration( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update user by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.USER_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "User name " + request.getNewUserName( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid new name " + request.getNewUserName( ) );
        } else if ( AuthException.INVALID_PATH.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid new path " + request.getNewPath( ) );
        }        
      }      
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteLoginProfileResponseType deleteLoginProfile(DeleteLoginProfileType request) throws EucalyptusCloudException {
    DeleteLoginProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    try {
      Privileged.deleteLoginProfile( requestUser, account, userFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete login profile for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UpdateSigningCertificateResponseType updateSigningCertificate(UpdateSigningCertificateType request) throws EucalyptusCloudException {
    UpdateSigningCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      Privileged.modifySigningCertificate( requestUser, account, userFound, request.getCertificateId( ), request.getStatus( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update signing certificate of " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.NO_SUCH_CERTIFICATE.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find the certificate " + request.getCertificateId( ) );
        } else if ( AuthException.EMPTY_STATUS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_VALUE, "Empty status" );
        } else if ( AuthException.EMPTY_CERT_ID.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_ID, "Empty certificate id" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteGroupPolicyResponseType deleteGroupPolicy(DeleteGroupPolicyType request) throws EucalyptusCloudException {
    DeleteGroupPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      Privileged.deleteGroupPolicy( requestUser, account, groupFound, request.getPolicyName( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete group policy of " + request.getGroupName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListUsersResponseType listUsers(ListUsersType request) throws EucalyptusCloudException {
    ListUsersResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    String path = "/";
    if ( !Strings.isNullOrEmpty( request.getPathPrefix( ) ) ) {
      path = request.getPathPrefix( );
    }
    if ( !Permissions.perhapsAuthorized( PolicySpec.VENDOR_IAM, PolicySpec.IAM_LISTUSERS, ctx.getAuthContext( ) ) ) {
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list users" );
    }
    ListUsersResultType result = reply.getListUsersResult( );
    result.setIsTruncated( false );
    ArrayList<UserType> users = reply.getListUsersResult( ).getUsers( ).getMemberList( );
    try {
      for ( final EuareUser user : account.getUsers( ) ) {
        if ( user.getPath( ).startsWith( path ) ) {
          if ( Privileged.allowListUser( requestUser, account, user ) ) {
            UserType u = new UserType( );
            fillUserResult( u, user, account );
            users.add( u );
          }
        }
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UpdateGroupResponseType updateGroup(UpdateGroupType request) throws EucalyptusCloudException {
    UpdateGroupResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      Privileged.modifyGroup( requestUser, account, groupFound, request.getNewGroupName( ), request.getNewPath( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update group " + groupFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.GROUP_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Group name " + request.getNewGroupName( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid new name " + request.getNewGroupName( ) );
        } else if ( AuthException.INVALID_PATH.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid new path " + request.getNewPath( ) );
        }        
      }   
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetServerCertificateResponseType getServerCertificate(GetServerCertificateType request) throws EucalyptusCloudException {
    final GetServerCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final String certName = request.getServerCertificateName();
    if(certName == null || certName.length()<=0)
      throw new EuareException(HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Certificate name is empty");
    
    try{
      final ServerCertificate cert = Privileged.getServerCertificate(requestUser, account, certName);
      final GetServerCertificateResultType result = new GetServerCertificateResultType();
      final ServerCertificateType certType = new ServerCertificateType();
      certType.setCertificateBody(cert.getCertificateBody());
      certType.setCertificateChain(cert.getCertificateChain());
      certType.setServerCertificateMetadata(getServerCertificateMetadata(cert));
      result.setServerCertificate(certType);
      reply.setGetServerCertificateResult(result);
    }catch(final AuthException ex){
      if ( AuthException.ACCESS_DENIED.equals( ex.getMessage( ) ) ) 
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get server certificate by " + ctx.getUser( ).getName( ) );
      else if (AuthException.SERVER_CERT_NO_SUCH_ENTITY.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Server certificate "+certName+" does not exist");
      else{
        LOG.error("failed to get server certificate", ex);
        throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
      }
    }catch(final Exception ex){
      LOG.error("failed to get server certificate", ex);
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE); 
    }
    reply.set_return(true);
    return reply;
  }

  public PutGroupPolicyResponseType putGroupPolicy(PutGroupPolicyType request) throws EucalyptusCloudException {
    PutGroupPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      Privileged.putGroupPolicy( requestUser, account, groupFound, request.getPolicyName( ), request.getPolicyDocument( ) );
    } catch ( PolicyParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_POLICY_DOCUMENT, "Error in uploaded policy: " + e.getMessage(), e );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to put group policy for " + groupFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid policy name " + request.getPolicyName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public CreateUserResponseType createUser(CreateUserType request) throws EucalyptusCloudException {
    CreateUserResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    try {
      EuareUser newUser = Privileged.createUser( requestUser, account, request.getUserName( ), sanitizePath( request.getPath( ) ) );
      UserType u = reply.getCreateUserResult( ).getUser( );
      fillUserResult( u, newUser, account );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create user by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "User quota exceeded" );
        } else if ( AuthException.USER_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "User " + request.getUserName( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid user name " + request.getUserName( ) );
        } else if ( AuthException.INVALID_PATH.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid user path " + request.getPath( ) );
        }        
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteSigningCertificateResponseType deleteSigningCertificate(DeleteSigningCertificateType request) throws EucalyptusCloudException {
    DeleteSigningCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      Privileged.deleteSigningCertificate( requestUser, account, userFound, request.getCertificateId( ) );
      return reply;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete cert for user " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_CERT_ID.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_ID, "Empty certificate id" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  public EnableMFADeviceResponseType enableMFADevice(EnableMFADeviceType request) throws EucalyptusCloudException {
    //EnableMFADeviceResponseType reply = request.getReply( );
    throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.NOT_IMPLEMENTED, "Operation not implemented" );
    //return reply;
  }

  public ListUserPoliciesResponseType listUserPolicies(ListUserPoliciesType request) throws EucalyptusCloudException {
    ListUserPoliciesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    ListUserPoliciesResultType result = reply.getListUserPoliciesResult( );
    result.setIsTruncated( false );
    ArrayList<String> policies = result.getPolicyNames( ).getMemberList( );
    try {
      for ( Policy p : Privileged.listUserPolicies( requestUser, account, userFound ) ) {
        policies.add( p.getName( ) );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list user policies for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListAccessKeysResponseType listAccessKeys(ListAccessKeysType request) throws EucalyptusCloudException {
    ListAccessKeysResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) || request.getDelegateAccount( ) != null ) {
      userFound = lookupUserByName( account, Objects.firstNonNull(
          Strings.emptyToNull( request.getUserName( ) ),
          userFound.getName( ) ) );
    }
    ListAccessKeysResultType result = reply.getListAccessKeysResult( );
    try {
      result.setIsTruncated( false );
      ArrayList<AccessKeyMetadataType> keys = result.getAccessKeyMetadata( ).getMemberList( );
      for ( AccessKey k : Privileged.listAccessKeys( requestUser, account, userFound ) ) {
        AccessKeyMetadataType key = new AccessKeyMetadataType( );
        key.setUserName( userFound.getName( ) );
        key.setAccessKeyId( k.getAccessKey( ) );
        key.setStatus( k.isActive( ) ? "Active" : "Inactive" );
        keys.add( key );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list access keys for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetLoginProfileResponseType getLoginProfile(GetLoginProfileType request) throws EucalyptusCloudException {
    GetLoginProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    if ( userFound.getPassword( ) == null ) {
      throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find login profile for " + request.getUserName( ) );
    }
    try {
      if ( !Privileged.allowReadLoginProfile( requestUser, account, userFound ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED,
                                  "Not authorized to get login profile for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );        
      }
      reply.getGetLoginProfileResult( ).getLoginProfile( ).setUserName( request.getUserName( ) );
      return reply;
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  public ListGroupsForUserResponseType listGroupsForUser(ListGroupsForUserType request) throws EucalyptusCloudException {
    ListGroupsForUserResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    reply.getListGroupsForUserResult( ).setIsTruncated( false );
    ArrayList<GroupType> groups = reply.getListGroupsForUserResult( ).getGroups( ).getMemberList( );
    try {
      for ( EuareGroup group : Privileged.listGroupsForUser( requestUser, account, userFound ) ) {
        // TODO(Ye Wen, 01/16/2011): do we need to check permission here?
        GroupType g = new GroupType( );
        fillGroupResult( g, group, account );
        groups.add( g );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get user groups for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public CreateGroupResponseType createGroup(CreateGroupType request) throws EucalyptusCloudException {
    CreateGroupResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    try {
      EuareGroup newGroup = Privileged.createGroup( requestUser, account, request.getGroupName( ), sanitizePath( request.getPath( ) ) );
      GroupType g = reply.getCreateGroupResult( ).getGroup( );
      fillGroupResult( g, newGroup, account );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create group by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Group quota exceeded" );
        } else if ( AuthException.GROUP_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Group " + request.getGroupName( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid group name " + request.getGroupName( ) );
        } else if ( AuthException.INVALID_PATH.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid group path " + request.getPath( ) );
        }        
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UploadServerCertificateResponseType uploadServerCertificate(UploadServerCertificateType request) throws EucalyptusCloudException {
    final UploadServerCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final String pemCertBody = request.getCertificateBody();
    final String pemCertChain = request.getCertificateChain();
    final String path = Objects.firstNonNull( request.getPath(), "/" );
    final String certName = request.getServerCertificateName();
    final String pemPk = request.getPrivateKey();
    try{
      Privileged.createServerCertificate( requestUser, account, pemCertBody, pemCertChain, path, certName, pemPk );
    }catch( final AuthException ex){
      if ( AuthException.ACCESS_DENIED.equals( ex.getMessage( ) ) )
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to upload server certificate by " + ctx.getUser( ).getName( ) );
      else if(AuthException.SERVER_CERT_ALREADY_EXISTS.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Server certificate "+ certName+ " already exists.");
      else if(AuthException.INVALID_SERVER_CERT_NAME.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Server certificate name "+certName+" is invalid format.");
      else if(AuthException.INVALID_SERVER_CERT_PATH.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Path "+path+" is invalid.");
      else if ( AuthException.QUOTA_EXCEEDED.equals( ex.getMessage( ) ) )
        throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Server certificate quota exceeded" );
      else if ( AuthException.SERVER_CERT_INVALID_FORMAT.equals(ex.getMessage()) ||
          ( ex.getMessage() != null && ex.getMessage().startsWith(AuthException.SERVER_CERT_INVALID_FORMAT)))
        throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_CERTIFICATE, ex.getMessage());
      else {
        throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
      }
    }catch( final Exception ex){
      LOG.error("Failed to create server certificate", ex);
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
    }
    try{
      final UploadServerCertificateResultType result = new UploadServerCertificateResultType();
      final ServerCertificateMetadataType metadata = 
          getServerCertificateMetadata(account.lookupServerCertificate(certName));
      result.setServerCertificateMetadata(metadata);
      reply.setUploadServerCertificateResult(result);
    }catch(final Exception ex){
      LOG.error("Failed to set certificate metadata", ex);
    }
    reply.set_return(true);
    return reply;
  }

  public GetGroupPolicyResponseType getGroupPolicy(GetGroupPolicyType request) throws EucalyptusCloudException {
    GetGroupPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      Policy policy = Privileged.getGroupPolicy( requestUser, account, groupFound, request.getPolicyName( ) );
      if ( policy != null ) {
        GetGroupPolicyResultType result = reply.getGetGroupPolicyResult( );
        result.setGroupName( request.getGroupName( ) );
        result.setPolicyName( request.getPolicyName( ) );
        result.setPolicyDocument( encodePolicy( policy.getText( ) ) );
      } else {
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find policy " + request.getPolicyName( ) );
      }
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get group policy for " + request.getGroupName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteUserResponseType deleteUser(DeleteUserType request) throws EucalyptusCloudException {
    DeleteUserResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userToDelete = lookupUserByName( account, request.getUserName( ) );
    try {
      boolean recursive = request.getIsRecursive( ) != null && request.getIsRecursive( );
      Privileged.deleteUser( requestUser, account, userToDelete, recursive );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete user by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.USER_DELETE_CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DELETE_CONFLICT, "Attempted to delete a user with resource attached by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeactivateMFADeviceResponseType deactivateMFADevice(DeactivateMFADeviceType request) throws EucalyptusCloudException {
    //DeactivateMFADeviceResponseType reply = request.getReply( );
    throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.NOT_IMPLEMENTED, "Operation not implemented" );
    //return reply;
  }

  public RemoveUserFromGroupResponseType removeUserFromGroup(RemoveUserFromGroupType request) throws EucalyptusCloudException {
    RemoveUserFromGroupResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    User userFound = lookupUserByName( account, request.getUserName( ) );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      Privileged.removeUserFromGroup( requestUser, account, userFound, groupFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to remove user from group by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteServerCertificateResponseType deleteServerCertificate(DeleteServerCertificateType request) throws EucalyptusCloudException {
    final DeleteServerCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    String certName = request.getServerCertificateName();
    if(certName == null || certName.length()<=0)
      throw new EuareException(HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Certificate name is empty");
    
    try{
      Privileged.deleteServerCertificate( requestUser, account, certName );
    }catch(final AuthException ex){
      if ( AuthException.ACCESS_DENIED.equals( ex.getMessage( ) ) ) 
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete server certificates by " + ctx.getUser( ).getName( ) );
      else if (AuthException.SERVER_CERT_NO_SUCH_ENTITY.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Server ceritifcate "+certName+" does not exist");
      else if (AuthException.SERVER_CERT_DELETE_CONFLICT.equals(ex.getMessage()))
        throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DELETE_CONFLICT, "Server certificate "+certName+" is in use");
      else{
        LOG.error("failed to delete server certificate", ex);
        throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
      }
    }catch(final Exception ex){
      LOG.error("failed to delete server certificate", ex);
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE); 
    }
    reply.set_return(true);

    return reply;
  }

  public ListGroupPoliciesResponseType listGroupPolicies(ListGroupPoliciesType request) throws EucalyptusCloudException {
    ListGroupPoliciesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    ListGroupPoliciesResultType result = reply.getListGroupPoliciesResult( );
    result.setIsTruncated( false );
    ArrayList<String> policies = result.getPolicyNames( ).getMemberList( );
    try {
      for ( Policy p : Privileged.listGroupPolicies( requestUser, account, groupFound ) ) {
        policies.add( p.getName( ) );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list group polices for " + request.getGroupName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public CreateLoginProfileResponseType createLoginProfile(CreateLoginProfileType request) throws EucalyptusCloudException {
    CreateLoginProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    if ( userFound.getPassword( ) != null ) {
      throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "User " + userFound.getName( ) + " already has a login profile" );
    }
    try {
      Privileged.createLoginProfile( requestUser, account, userFound, request.getPassword( ) );
      reply.getCreateLoginProfileResult( ).getLoginProfile( ).setUserName( ctx.getUser( ).getName( ) );
      return reply;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create login profile for " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.INVALID_PASSWORD.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid password" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  public CreateAccessKeyResponseType createAccessKey(CreateAccessKeyType request) throws EucalyptusCloudException {
    CreateAccessKeyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      AccessKey key = Privileged.createAccessKey( requestUser, account, userFound );
      AccessKeyType keyResult = reply.getCreateAccessKeyResult( ).getAccessKey( );
      keyResult.setAccessKeyId( key.getAccessKey( ) );
      keyResult.setCreateDate( key.getCreateDate( ) );
      keyResult.setSecretAccessKey( key.getSecretKey( ) );
      keyResult.setStatus( key.isActive( ) ? "Active" : "Inactive" );
      keyResult.setUserName( userFound.getName( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create access key for user " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Access key limit exceeded" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetUserResponseType getUser(GetUserType request) throws EucalyptusCloudException {
    GetUserResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      if ( !Privileged.allowReadUser( requestUser, account, userFound ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get user by " + ctx.getUser( ).getName( ) );
      }
      UserType u = reply.getGetUserResult( ).getUser( );
      fillUserResult( u, userFound, account );
      if ( request.getShowExtra( ) != null && request.getShowExtra( ) ) {
        fillUserResultExtra( u, userFound );
      }
      return reply;
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  public ResyncMFADeviceResponseType resyncMFADevice(ResyncMFADeviceType request) throws EucalyptusCloudException {
    //ResyncMFADeviceResponseType reply = request.getReply( );
    throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.NOT_IMPLEMENTED, "Operation not implemented" );
    //return reply;
  }

  public ListMFADevicesResponseType listMFADevices(ListMFADevicesType request) throws EucalyptusCloudException {
    //ListMFADevicesResponseType reply = request.getReply( );
    throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.NOT_IMPLEMENTED, "Operation not implemented" );
    //return reply;
  }

  public UpdateAccessKeyResponseType updateAccessKey(UpdateAccessKeyType request) throws EucalyptusCloudException {
    UpdateAccessKeyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      Privileged.modifyAccessKey( requestUser, account, userFound, request.getAccessKeyId( ), request.getStatus( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update access key of " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_KEY_ID.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_ID, "Empty access key id" );
        } else if ( AuthException.EMPTY_STATUS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_VALUE, "Empty status" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public AddUserToGroupResponseType addUserToGroup(AddUserToGroupType request) throws EucalyptusCloudException {
    AddUserToGroupResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    User userFound = lookupUserByName( account, request.getUserName( ) );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    // TODO(Ye Wen, 01/22/2011): add group level quota?
    try {
      Privileged.addUserToGroup( requestUser, account, userFound, groupFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to add user to group by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetGroupResponseType getGroup(GetGroupType request) throws EucalyptusCloudException {
    GetGroupResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      if ( !Privileged.allowReadGroup( requestUser, account, groupFound ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get group " + request.getGroupName( ) + " by " + ctx.getUser( ).getName( ) );
      }
      reply.getGetGroupResult( ).setIsTruncated( false );
      GroupType g = reply.getGetGroupResult( ).getGroup( );
      fillGroupResult( g, groupFound, account );
      ArrayList<UserType> users = reply.getGetGroupResult( ).getUsers( ).getMemberList( );
      for ( EuareUser user : groupFound.getUsers( ) ) {
        UserType u = new UserType( );
        fillUserResult( u, user, account );
        users.add( u );
      }
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteGroupResponseType deleteGroup(DeleteGroupType request) throws EucalyptusCloudException {
    DeleteGroupResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareGroup groupFound = lookupGroupByName( account, request.getGroupName( ) );
    try {
      boolean recursive = request.getIsRecursive( ) != null && request.getIsRecursive( );
      Privileged.deleteGroup( requestUser, account, groupFound, recursive );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete group by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.GROUP_DELETE_CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DELETE_CONFLICT, "Attempted to delete group with resources attached by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public CreateAccountAliasResponseType createAccountAlias(CreateAccountAliasType request) throws EucalyptusCloudException {
    CreateAccountAliasResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    try {
      Privileged.modifyAccount( requestUser, account, request.getAccountAlias( ) );
      return reply;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create account alias by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Can not change to a name already in use: " + request.getAccountAlias( ) );
        } else if ( AuthException.ACCOUNT_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Account alias " + request.getAccountAlias( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid account alias " + request.getAccountAlias( ) );
        }
      }    
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }
  
  public DeleteAccountAliasResponseType deleteAccountAlias(DeleteAccountAliasType request) throws EucalyptusCloudException {
    DeleteAccountAliasResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    try {
      Privileged.deleteAccountAlias( requestUser, account, request.getAccountAlias( ) );
      return reply;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete account alias by " + ctx.getUser( ).getName( ) );          
        } else if ( AuthException.EMPTY_ACCOUNT_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_VALUE, "Empty account alias" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  public ListAccountAliasesResponseType listAccountAliases(ListAccountAliasesType request) throws EucalyptusCloudException {
    ListAccountAliasesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    try {
      reply.getListAccountAliasesResult( ).getAccountAliases( ).getMemberList( ).addAll( Privileged.listAccountAliases( requestUser, account ) );
      return reply;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list account aliases by " + ctx.getUser( ).getName( ) );          
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }
  
  public GetAccountSummaryResponseType getAccountSummary(GetAccountSummaryType request) throws EucalyptusCloudException {
    GetAccountSummaryResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    try {
      account = Privileged.getAccountSummary( requestUser, account );
      List<SummaryMapTypeEntryType> map = reply.getGetAccountSummaryResult( ).getSummaryMap( ).getEntryList( );
      map.add( new SummaryMapTypeEntryType( "Groups", account.getGroups().size() ) );
      map.add( new SummaryMapTypeEntryType( "Users", account.getUsers().size() ) );
      map.add( new SummaryMapTypeEntryType( "Roles", account.getRoles().size( ) ) );
      map.add( new SummaryMapTypeEntryType( "InstanceProfiles", account.getInstanceProfiles().size( ) ) );
      map.add( new SummaryMapTypeEntryType( "ServerCertificates", account.listServerCertificates("/").size()));
      return reply;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get account summary by " + ctx.getUser( ).getName( ) );          
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }
  
  public CreateSigningCertificateResponseType createSigningCertificate(CreateSigningCertificateType request) throws EucalyptusCloudException {
    CreateSigningCertificateResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      KeyPair keyPair = Certs.generateKeyPair( );
      Certificate cert = Privileged.createSigningCertificate( requestUser, account, userFound, keyPair );
      SigningCertificateType result = reply.getCreateSigningCertificateResult( ).getCertificate( );
      result.setUserName( userFound.getName( ) );
      result.setCertificateId( cert.getCertificateId( ) );
      result.setCertificateBody( B64.url.decString( cert.getPem( ) ) );
      result.setPrivateKey( X509CertHelper.privateKeyToPem( keyPair.getPrivate( ) ) );
      result.setStatus( "Active" );
      result.setUploadDate( cert.getCreateDate( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create signing certificate of " + request.getUserName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Signing certificate limit exceeded" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public GetUserInfoResponseType getUserInfo(GetUserInfoType request) throws EucalyptusCloudException {
    GetUserInfoResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUser( ctx );
    if ( !Strings.isNullOrEmpty( request.getUserName( ) ) ) {
      userFound = lookupUserByName( account, request.getUserName( ) );
    }
    try {
      if ( !Privileged.allowReadUser( requestUser, account, userFound ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get user info by " + ctx.getUser( ).getName( ) );
      }
      ArrayList<UserInfoType> infos = reply.getGetUserInfoResult( ).getInfos( ).getMemberList( );
      if ( !Strings.isNullOrEmpty( request.getInfoKey( ) ) ) {
        String value = userFound.getInfo( request.getInfoKey( ) );
        if ( value != null ) {
          UserInfoType ui = new UserInfoType( );
          ui.setKey( request.getInfoKey( ).toLowerCase( ) );
          ui.setValue( value );
          infos.add( ui );
        }
      } else {
        for ( Map.Entry<String, String> entry : userFound.getInfo( ).entrySet( ) ) {
          UserInfoType ui = new UserInfoType();
          ui.setKey( entry.getKey( ) );
          ui.setValue( entry.getValue( ) );
          infos.add( ui );
        }
      }
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public UpdateUserInfoResponseType updateUserInfo(UpdateUserInfoType request) throws EucalyptusCloudException {
    UpdateUserInfoResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount account = getRealAccount( ctx, request );
    EuareUser userFound = lookupUserByName( account, request.getUserName( ) );
    if ( Strings.isNullOrEmpty( request.getInfoKey( ) ) ) {
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty key name" );
    }
    try {
      Privileged.updateUserInfoItem( requestUser, account, userFound, request.getInfoKey( ), request.getInfoValue( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update user by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public PutAccountPolicyResponseType putAccountPolicy(PutAccountPolicyType request) throws EucalyptusCloudException {
    PutAccountPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount accountFound = lookupAccountByName( request.getAccountName( ) );
    try {
      Privileged.putAccountPolicy( requestUser, accountFound, request.getPolicyName( ), request.getPolicyDocument( ) );
    } catch ( PolicyParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_POLICY_DOCUMENT, "Error in uploaded policy: " + e.getMessage(), e );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to put account policy for " + accountFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid policy name " + request.getPolicyName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public ListAccountPoliciesResponseType listAccountPolicies(ListAccountPoliciesType request) throws EucalyptusCloudException {
    ListAccountPoliciesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount accountFound = lookupAccountByName( request.getAccountName( ) );
    ListAccountPoliciesResultType result = reply.getListAccountPoliciesResult( );
    result.setIsTruncated( false );
    ArrayList<String> policies = result.getPolicyNames( ).getMemberList( );
    try {
      for ( Policy p : Privileged.listAccountPolicies( requestUser, accountFound ) ) {
        policies.add( p.getName( ) );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list account policies for " + accountFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public GetAccountPolicyResponseType getAccountPolicy(GetAccountPolicyType request) throws EucalyptusCloudException {
    GetAccountPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount accountFound = lookupAccountByName( request.getAccountName( ) );
    try {
      Policy policy = Privileged.getAccountPolicy( requestUser, accountFound, request.getPolicyName( ) );
      if ( policy != null ) {
        GetAccountPolicyResultType result = reply.getGetAccountPolicyResult( );
        result.setAccountName( request.getAccountName( ) );
        result.setPolicyName( request.getPolicyName( ) );
        result.setPolicyDocument( encodePolicy( policy.getText( ) ) );
      } else {
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find policy " + request.getPolicyName( ) );
      }
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get account policy for " + accountFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }
  
  public DeleteAccountPolicyResponseType deleteAccountPolicy(DeleteAccountPolicyType request) throws EucalyptusCloudException {
    DeleteAccountPolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    EuareAccount accountFound = lookupAccountByName( request.getAccountName( ) );
    try {
      Privileged.deleteAccountPolicy( requestUser, accountFound, request.getPolicyName( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete account policy for " + accountFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public CreateRoleResponseType createRole( final CreateRoleType request ) throws EucalyptusCloudException {
    final CreateRoleResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    try {
      final EuareRole newRole = Privileged.createRole( requestUser, account, request.getRoleName( ), sanitizePath( request.getPath( ) ), request.getAssumeRolePolicyDocument() );
      reply.getCreateRoleResult( ).setRole( fillRoleResult( new RoleType(), newRole ) );
    } catch ( PolicyParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_POLICY_DOCUMENT, "Error in uploaded policy: " + e.getMessage(), e );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create role by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Role quota exceeded" );
        } else if ( AuthException.ROLE_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Role " + request.getRoleName( ) + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid role name " + request.getRoleName() );
        } else if ( AuthException.INVALID_PATH.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid role path " + request.getPath( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public UpdateAssumeRolePolicyResponseType updateAssumeRolePolicy( final UpdateAssumeRolePolicyType request ) throws EucalyptusCloudException {
    final UpdateAssumeRolePolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    try {
      Privileged.updateAssumeRolePolicy( requestUser, account, roleFound, request.getPolicyDocument() );
    } catch ( PolicyParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_POLICY_DOCUMENT, "Error in uploaded policy: " + e.getMessage(), e );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to update role " + roleFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetRoleResponseType getRole( final GetRoleType request ) throws EucalyptusCloudException {
    final GetRoleResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    try {
      if ( !Privileged.allowReadRole( requestUser, account, roleFound ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get role " + request.getRoleName() + " by " + ctx.getUser( ).getName( ) );
      }
      reply.getGetRoleResult( ).setRole( fillRoleResult( new RoleType(), roleFound ) );
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }

    return reply;
  }

  public DeleteRoleResponseType deleteRole( final DeleteRoleType request ) throws EucalyptusCloudException {
    final DeleteRoleResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    try {
      Privileged.deleteRole( requestUser, account, roleFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete role by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.ROLE_DELETE_CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.DELETE_CONFLICT, "Attempted to delete role with resources attached by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListRolesResponseType listRoles( final ListRolesType request ) throws EucalyptusCloudException {
    final ListRolesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    String path = "/";
    if ( !Strings.isNullOrEmpty( request.getPathPrefix( ) ) ) {
      path = request.getPathPrefix( );
    }
    if ( !Permissions.perhapsAuthorized( PolicySpec.VENDOR_IAM, PolicySpec.IAM_LISTROLES, ctx.getAuthContext( ) ) ) {
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list roles" );
    }
    reply.getListRolesResult( ).setIsTruncated( false );
    final ArrayList<RoleType> roles = reply.getListRolesResult( ).getRoles().getMember();
    try ( final AutoCloseable euareTx = readonlyTx( ) ) {
      for ( final EuareRole role : account.getRoles() ) {
        if ( role.getPath( ).startsWith( path ) ) {
          if ( Privileged.allowListRole( requestUser, account, role ) ) {
            roles.add( fillRoleResult( new RoleType( ), role ) );
          }
        }
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public PutRolePolicyResponseType putRolePolicy( final PutRolePolicyType request ) throws EucalyptusCloudException {
    final PutRolePolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    try {
      Privileged.putRolePolicy( requestUser, account, roleFound, request.getPolicyName( ), request.getPolicyDocument( ) );
    } catch ( PolicyParseException e ) {
      LOG.error( e, e );
      throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.MALFORMED_POLICY_DOCUMENT, "Error in uploaded policy: " + e.getMessage(), e );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to put role policy for " + roleFound.getName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid policy name " + request.getPolicyName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetRolePolicyResponseType getRolePolicy( final GetRolePolicyType request ) throws EucalyptusCloudException {
    final GetRolePolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    try {
      final Policy policy = Privileged.getRolePolicy( requestUser, account, roleFound, request.getPolicyName( ) );
      if ( policy != null ) {
        GetRolePolicyResult result = reply.getGetRolePolicyResult( );
        result.setRoleName( request.getRoleName( ) );
        result.setPolicyName( request.getPolicyName( ) );
        result.setPolicyDocument( encodePolicy( policy.getText( ) ) );
      } else {
        throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find policy " + request.getPolicyName( ) );
      }
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get role policy for " + request.getRoleName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteRolePolicyResponseType deleteRolePolicy( final DeleteRolePolicyType request ) throws EucalyptusCloudException {
    final DeleteRolePolicyResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    try {
      Privileged.deleteRolePolicy( requestUser, account, roleFound, request.getPolicyName( ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete role policy of " + request.getRoleName( ) + " by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.EMPTY_POLICY_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty policy name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListRolePoliciesResponseType listRolePolicies( final ListRolePoliciesType request ) throws EucalyptusCloudException {
    final ListRolePoliciesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    final ListRolePoliciesResult result = reply.getListRolePoliciesResult( );
    result.setIsTruncated( false );
    final ArrayList<String> policies = result.getPolicyNames().getMemberList( );
    try {
      for ( Policy p : Privileged.listRolePolicies( requestUser, account, roleFound ) ) {
        policies.add( p.getName( ) );
      }
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list role polices for " + request.getRoleName( ) + " by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public CreateInstanceProfileResponseType createInstanceProfile( final CreateInstanceProfileType request ) throws EucalyptusCloudException {
    final CreateInstanceProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    try {
      final EuareInstanceProfile newInstanceProfile = Privileged.createInstanceProfile( requestUser, account, request.getInstanceProfileName(), sanitizePath( request.getPath() ) );
      reply.getCreateInstanceProfileResult().setInstanceProfile( fillInstanceProfileResult( new InstanceProfileType(), newInstanceProfile ) );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to create instance profile by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.QUOTA_EXCEEDED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.LIMIT_EXCEEDED, "Instance profile quota exceeded" );
        } else if ( AuthException.INSTANCE_PROFILE_ALREADY_EXISTS.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Instance profile " + request.getInstanceProfileName() + " already exists." );
        } else if ( AuthException.INVALID_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid instance profile name " + request.getInstanceProfileName() );
        } else if ( AuthException.INVALID_PATH.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.VALIDATION_ERROR, "Invalid instance profile path " + request.getPath( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetInstanceProfileResponseType getInstanceProfile( final GetInstanceProfileType request ) throws EucalyptusCloudException {
    final GetInstanceProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareInstanceProfile instanceProfileFound = lookupInstanceProfileByName( account, request.getInstanceProfileName() );
    try {
      if ( !Privileged.allowReadInstanceProfile( requestUser, account, instanceProfileFound ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get instance profile " + request.getInstanceProfileName() + " by " + ctx.getUser( ).getName( ) );
      }
      reply.getGetInstanceProfileResult().setInstanceProfile( fillInstanceProfileResult( new InstanceProfileType(), instanceProfileFound ) );
    } catch ( EuareException e ) {
      throw e;
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public AddRoleToInstanceProfileResponseType addRoleToInstanceProfile( final AddRoleToInstanceProfileType request ) throws EucalyptusCloudException {
    final AddRoleToInstanceProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    final EuareInstanceProfile instanceProfileFound = lookupInstanceProfileByName( account, request.getInstanceProfileName() );
    try {
      Privileged.addRoleToInstanceProfile( requestUser, account, instanceProfileFound, roleFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to add role to instance profile by " + ctx.getUser( ).getName( ) );
        } else if ( AuthException.CONFLICT.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.CONFLICT, EuareException.ENTITY_ALREADY_EXISTS, "Role " + request.getRoleName( ) + " is already in the instance profile " + request.getInstanceProfileName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public RemoveRoleFromInstanceProfileResponseType removeRoleFromInstanceProfile( final RemoveRoleFromInstanceProfileType request ) throws EucalyptusCloudException {
    final RemoveRoleFromInstanceProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    final EuareInstanceProfile instanceProfileFound = lookupInstanceProfileByName( account, request.getInstanceProfileName() );
    try {
      Privileged.removeRoleFromInstanceProfile( requestUser, account, instanceProfileFound, roleFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to remove role from instance profile by " + ctx.getUser( ).getName( ) );
        }

      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListInstanceProfilesForRoleResponseType listInstanceProfilesForRole( final ListInstanceProfilesForRoleType request ) throws EucalyptusCloudException {
    final ListInstanceProfilesForRoleResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareRole roleFound = lookupRoleByName( account, request.getRoleName() );
    reply.getListInstanceProfilesForRoleResult().setIsTruncated( false );
    final ArrayList<InstanceProfileType> instanceProfiles = reply.getListInstanceProfilesForRoleResult().getInstanceProfiles().getMember();
    try ( final AutoCloseable euareTx = readonlyTx( ) ) {
      for ( final EuareInstanceProfile instanceProfile : Privileged.listInstanceProfilesForRole( requestUser, account, roleFound ) ) {
        if ( Privileged.allowListInstanceProfileForRole( requestUser, account, instanceProfile ) ) {
          instanceProfiles.add( fillInstanceProfileResult( new InstanceProfileType(), instanceProfile ) );
        }
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public DeleteInstanceProfileResponseType deleteInstanceProfile( final DeleteInstanceProfileType request ) throws EucalyptusCloudException {
    final DeleteInstanceProfileResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    final EuareInstanceProfile instanceProfileFound = lookupInstanceProfileByName( account, request.getInstanceProfileName() );
    try {
      Privileged.deleteInstanceProfile( requestUser, account, instanceProfileFound );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.ACCESS_DENIED.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to delete instance profile by " + ctx.getUser( ).getName( ) );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public ListInstanceProfilesResponseType listInstanceProfiles( final ListInstanceProfilesType request ) throws EucalyptusCloudException {
    final ListInstanceProfilesResponseType reply = request.getReply( );
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    final EuareAccount account = getRealAccount( ctx, request );
    String path = "/";
    if ( !Strings.isNullOrEmpty( request.getPathPrefix( ) ) ) {
      path = request.getPathPrefix( );
    }
    if ( !Permissions.perhapsAuthorized( PolicySpec.VENDOR_IAM, PolicySpec.IAM_LISTINSTANCEPROFILES, ctx.getAuthContext( ) ) ) {
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to list instance profiles" );
    }
    reply.getListInstanceProfilesResult().setIsTruncated( false );
    final ArrayList<InstanceProfileType> instanceProfiles = reply.getListInstanceProfilesResult( ).getInstanceProfiles().getMember();
    try ( final AutoCloseable euareTx = readonlyTx( ) ) {
      for ( final EuareInstanceProfile instanceProfile : (List<EuareInstanceProfile>)(List)account.getInstanceProfiles() ) {
        if ( instanceProfile.getPath( ).startsWith( path ) ) {
          if ( Privileged.allowListInstanceProfile( requestUser, account, instanceProfile ) ) {
            instanceProfiles.add( fillInstanceProfileResult( new InstanceProfileType(), instanceProfile ) );
          }
        }
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
    return reply;
  }

  public GetLdapSyncStatusResponseType getLdapSyncStatus(GetLdapSyncStatusType request) throws EucalyptusCloudException {
    GetLdapSyncStatusResponseType reply = request.getReply( );
    Context ctx = Contexts.lookup( );
    AuthContext requestUser = getAuthContext( ctx );
    if ( !requestUser.isSystemAdmin( ) ) {
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Not authorized to get LDAP sync status by " + ctx.getUser( ).getName( ) );
    }
    reply.getResponseMetadata( ).setRequestId( reply.getCorrelationId( ) );
    reply.getGetLdapSyncStatusResult( ).setSyncEnabled( LdapSync.getLic( ).isSyncEnabled( ) );
    reply.getGetLdapSyncStatusResult( ).setInSync( LdapSync.inSync( ) );
    return reply;
  }
  
  /* Euca-only API for ELB SSL termination */
  public DownloadServerCertificateResponseType downloadCertificate(DownloadServerCertificateType request) throws EucalyptusCloudException {
    final DownloadServerCertificateResponseType reply = request.getReply();
    final Context ctx = Contexts.lookup( );
    final AuthContext requestUser = getAuthContext( ctx );
    
    /// For now, the users (role) that can download server cert should belong to eucalyptus account
    if( !requestUser.isSystemUser( ) ){
      throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED,"The user not authorized to perform action");
    }
    
    final String sigB64 = request.getSignature();
    final Date ts = request.getTimestamp();
    final String certPem = request.getDelegationCertificate();
    final String authSigB64 = request.getAuthSignature();
    final String certArn = request.getCertificateArn();
    boolean oldCertType = false;
    try{
      if(!EuareServerCertificateUtil.verifyCertificate(certPem, true)) {       
        // must be certificate type prior 4.1
        oldCertType = true;
        if( !EuareServerCertificateUtil.verifyCertificate(certPem, false))
          throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED,"Invalid VM certificate (certificate may have been expired)");
      }
    }catch(final EuareException ex) {
      throw ex;
    }catch(final Exception ex) {
      throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED,"Invalid VM certificate (certificate may have been expired)");
    }
    
    if(sigB64 == null || ts == null)
      throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Signature and timestamp are required");
    
    final Date now = new Date();
    long tsDiff = now.getTime() - ts.getTime();
    final long TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes
    if(tsDiff < 0 || Math.abs(tsDiff) > TIMEOUT_MS)
      throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Invalid timestamp");
    final TimeZone tz = TimeZone.getTimeZone("UTC");
    final DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    df.setTimeZone(tz);
    String tsAsIso = df.format(ts); 
    
    // verify signature of the request
    final String payload = String.format("%s&%s", certArn, tsAsIso);
    try{
        if(!EuareServerCertificateUtil.verifySignature(certPem, payload, sigB64))
          throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Invalid signature");
    }catch(final EuareException ex){
      throw ex;
    }catch(final Exception ex){       
      LOG.error("failed to verify signature", ex);
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
    }
   
    // No longer the case after EUCA-8651. verifyCertificate() will validate the cert. Left here for backward-compatibility
    if (oldCertType) {
      // verify signature issued by EUARE
      try{
        final String certStr = B64.standard.decString(certPem);
        if(!EuareServerCertificateUtil.verifySignatureWithEuare(certStr, authSigB64))
          throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Invalid signature");
      }catch(final EuareException ex){
        throw ex;
      }catch(final Exception ex){
        LOG.error("failed to verify auth signature", ex);
        throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
      }
    }
    try{
      // access control based on iam policy
      final ServerCertificateEntity cert = RestrictedTypes.doPrivilegedWithoutOwner(certArn, ServerCertificates.Lookup.INSTANCE);
    }catch(final AuthException ex){
      throw new EuareException(HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED,"The user not authorized to download certificate"); 
    }catch(final NoSuchElementException ex){
      throw new EuareException(HttpResponseStatus.BAD_REQUEST, EuareException.NO_SUCH_ENTITY,"Server certificate is not found");
    }catch(final Exception ex){
      throw new EuareException( HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
    }
  
    final DownloadServerCertificateResultType result = new DownloadServerCertificateResultType();
    try{
      result.setCertificateArn(certArn);
      final String serverCertPem = B64.standard.encString(EuareServerCertificateUtil.getServerCertificate(certArn));
      result.setServerCertificate( serverCertPem);
      
      final String pk = EuareServerCertificateUtil.getEncryptedKey(certArn, certPem);
      result.setServerPk(pk);
      final String msg = String.format("%s&%s", serverCertPem, pk);
      final String sig = EuareServerCertificateUtil.generateSignatureWithEuare(msg);
      result.setSignature(sig);
      reply.setDownloadServerCertificateResult(result);
    }catch(final Exception ex){
      LOG.error("failed to prepare server certificate", ex);
      throw new EuareException(HttpResponseStatus.INTERNAL_SERVER_ERROR, EuareException.INTERNAL_FAILURE);
    }
    return reply;
  }
  
  private void fillUserResult( UserType u, EuareUser userFound, EuareAccount account ) {
    u.setUserName( userFound.getName( ) );
    u.setUserId( userFound.getUserId( ) );
    u.setPath( userFound.getPath( ) );
    u.setArn( ( new EuareResourceName( account.getAccountNumber( ), PolicySpec.IAM_RESOURCE_USER, userFound.getPath( ), userFound.getName( ) ) ).toString( ) );
    u.setCreateDate( userFound.getCreateDate( ) );
  }
  
  private void fillUserResultExtra( UserType u, EuareUser userFound ) {
    u.setEnabled( String.valueOf( userFound.isEnabled() ) );
    u.setPasswordExpiration( new Date( userFound.getPasswordExpires() ).toString() );
  }
  
  private void fillGroupResult( GroupType g, EuareGroup groupFound, EuareAccount account ) {
    g.setPath( groupFound.getPath( ) );
    g.setGroupName( groupFound.getName() );
    g.setGroupId( groupFound.getGroupId() );
    g.setArn( (new EuareResourceName( account.getAccountNumber(), PolicySpec.IAM_RESOURCE_GROUP, groupFound.getPath(), groupFound.getName() )).toString() );
    g.setCreateDate( groupFound.getCreateDate( ) );
  }

  private InstanceProfileType fillInstanceProfileResult( InstanceProfileType instanceProfileType, EuareInstanceProfile instanceProfileFound ) throws AuthException {
    instanceProfileType.setInstanceProfileName( instanceProfileFound.getName() );
    instanceProfileType.setInstanceProfileId( instanceProfileFound.getInstanceProfileId() );
    instanceProfileType.setPath( instanceProfileFound.getPath() );
    instanceProfileType.setArn( Accounts.getInstanceProfileArn( instanceProfileFound ) );
    instanceProfileType.setCreateDate( instanceProfileFound.getCreationTimestamp() );
    final EuareRole role = instanceProfileFound.getRole();
    instanceProfileType.setRoles( role == null ? new RoleListType() : new RoleListType( fillRoleResult( new RoleType(), role ) ) );
    return instanceProfileType;
  }

  private RoleType fillRoleResult( RoleType roleType, EuareRole roleFound ) throws AuthException {
    roleType.setRoleName( roleFound.getName( ) );
    roleType.setRoleId( roleFound.getRoleId() );
    roleType.setPath( roleFound.getPath() );
    roleType.setAssumeRolePolicyDocument( encodePolicy( roleFound.getAssumeRolePolicy().getText() ) );
    roleType.setArn( Accounts.getRoleArn( roleFound ) );
    roleType.setCreateDate( roleFound.getCreationTimestamp() );
    return roleType;
  }

  private static AuthContext getAuthContext( final Context ctx ) throws EucalyptusCloudException {
    try {
      return ctx.getAuthContext( ).get( );
    } catch ( AuthException e ) {
      throw new EucalyptusCloudException( e );
    }
  }

  private EuareAccount getRealAccount( Context ctx, EuareMessageWithDelegate request ) throws EuareException {
    final EuareAccount requestAccount;
    final String delegateAccount = request.getDelegateAccount( );
    if ( delegateAccount != null ) {
      if ( ctx.isAdministrator( ) ) {
        try {
          EuareAccount account = Accounts.lookupAccountByName( delegateAccount );
          if ( RestrictedTypes.filterPrivileged( ).apply( account ) ) {
            return account;
          }
        } catch ( AuthException e ) {
          throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Can not find delegation account " + delegateAccount );
        }        
      }
      throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Delegation access not authorized for " + delegateAccount );
    } else {
      try {
        requestAccount = com.eucalyptus.auth.euare.Accounts.lookupAccountById( ctx.getAccountNumber() );
      } catch ( AuthException e ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Request account error " + ctx.getAccountNumber( ) );
      }
      if ( ctx.isAdministrator( ) && !RestrictedTypes.filterPrivileged().apply( requestAccount ) ) {
        throw new EuareException( HttpResponseStatus.FORBIDDEN, EuareException.NOT_AUTHORIZED, "Access not authorized for " + requestAccount );
      }
    }
    return requestAccount;
  }
  
  private static String sanitizePath( String path ) {
    if ( path == null || "".equals( path ) ) {
      return "/";
    } else if ( path.length( ) > 1 && !path.endsWith( "/" ) ) {
      return path.concat( "/" );
    }
    return path;
  }

  private static EuareUser lookupUser( Context context ) throws EucalyptusCloudException {
    return lookupUserById( context.getUser( ).getUserId( ) );
  }

  private static EuareUser lookupUserById( String userId ) throws EucalyptusCloudException {
    try {
      return Accounts.lookupUserById( userId );
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  private static EuareUser lookupUserByName( EuareAccount account, String userName ) throws EucalyptusCloudException  {
    try {
      return account.lookupUserByName( userName );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) { 
        if ( AuthException.NO_SUCH_USER.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find user " + userName );
        } else if ( AuthException.EMPTY_USER_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty user name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  private static EuareGroup lookupGroupByName( EuareAccount account, String groupName ) throws EucalyptusCloudException {
    try {
      return account.lookupGroupByName( groupName );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.NO_SUCH_GROUP.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find group " + groupName );
        } else if ( AuthException.EMPTY_GROUP_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty group name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  private static EuareInstanceProfile lookupInstanceProfileByName( EuareAccount account, String instanceProfileName ) throws EucalyptusCloudException {
    try {
      return account.lookupInstanceProfileByName( instanceProfileName );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.NO_SUCH_INSTANCE_PROFILE.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find instance profile " + instanceProfileName );
        } else if ( AuthException.EMPTY_INSTANCE_PROFILE_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty instance profile name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }


  private static EuareRole lookupRoleByName( EuareAccount account, String roleName ) throws EucalyptusCloudException {
    try {
      return account.lookupRoleByName( roleName );
    } catch ( Exception e ) {
      if ( e instanceof AuthException ) {
        if ( AuthException.NO_SUCH_ROLE.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find role " + roleName );
        } else if ( AuthException.EMPTY_ROLE_NAME.equals( e.getMessage( ) ) ) {
          throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty role name" );
        }
      }
      LOG.error( e, e );
      throw new EucalyptusCloudException( e );
    }
  }

  private static EuareAccount lookupAccountByName( String accountName ) throws EucalyptusCloudException {
    try {
      return Accounts.lookupAccountByName( accountName );
    } catch ( Exception e1 ) {
      try {
        // Try using ID
        return com.eucalyptus.auth.euare.Accounts.lookupAccountById( accountName );
      } catch ( Exception e ) {
        if ( e instanceof AuthException ) {
          if ( AuthException.NO_SUCH_ACCOUNT.equals( e.getMessage( ) ) ) {
            throw new EuareException( HttpResponseStatus.NOT_FOUND, EuareException.NO_SUCH_ENTITY, "Can not find account " + accountName );
          } else if ( AuthException.EMPTY_ACCOUNT_NAME.equals( e.getMessage( ) ) ) {
            throw new EuareException( HttpResponseStatus.BAD_REQUEST, EuareException.INVALID_NAME, "Empty account name" );
          }
        }
        LOG.error( e, e );
        throw new EucalyptusCloudException( e );
      }
    }
  }
  
  private static ServerCertificateMetadataType getServerCertificateMetadata(final ServerCertificate cert){
    final ServerCertificateMetadataType metadata = 
        new ServerCertificateMetadataType();
    metadata.setArn(cert.getArn());
    metadata.setServerCertificateId(cert.getCertificateId());
    metadata.setServerCertificateName(cert.getCertificateName());
    metadata.setPath(cert.getCertificatePath());
    metadata.setUploadDate(cert.getCreatedTime());
    return metadata;
  }
 
  private String encodePolicy( final String policy ) {
    try {
      return ENCODE_POLICIES && policy != null ?
          UriUtils.encodeScheme( policy, StandardCharsets.UTF_8.name( ) ) :
          policy;
    } catch ( final UnsupportedEncodingException e ) {
      throw Exceptions.toUndeclared( e );
    }
  }
  
  protected AutoCloseable readonlyTx( ) {
    return Entities.readOnlyDistinctTransactionFor( UserEntity.class );
  }
}
