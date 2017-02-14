/*
 * Copyright © 2016-2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.security.impersonation;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.http.DefaultHttpRequestConfig;
import co.cask.cdap.common.internal.remote.RemoteClient;
import co.cask.cdap.common.kerberos.ImpersonationInfo;
import co.cask.common.http.HttpMethod;
import co.cask.common.http.HttpRequest;
import co.cask.common.http.HttpResponse;
import com.google.gson.Gson;
import com.google.inject.Inject;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.twill.discovery.DiscoveryServiceClient;
import org.apache.twill.filesystem.Location;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/**
 * Makes requests to ImpersonationHandler to request credentials.
 */
public class RemoteUGIProvider extends AbstractCachedUGIProvider {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteUGIProvider.class);
  private static final Gson GSON = new Gson();

  private final RemoteClient remoteClient;
  private final LocationFactory locationFactory;

  @Inject
  RemoteUGIProvider(CConfiguration cConf, final DiscoveryServiceClient discoveryClient,
                    LocationFactory locationFactory) {
    super(cConf);
    this.remoteClient = new RemoteClient(discoveryClient, Constants.Service.APP_FABRIC_HTTP,
                                         new DefaultHttpRequestConfig(false), "/v1/");
    this.locationFactory = locationFactory;
  }

  @Override
  protected UserGroupInformation createUGI(ImpersonationInfo impersonationInfo) throws IOException {
    String credentialsURI = executeRequest(impersonationInfo).getResponseBodyAsString();
    LOG.debug("Received response: {}", credentialsURI);

    Location location = locationFactory.create(URI.create(credentialsURI));
    try {
      UserGroupInformation impersonatedUGI = UserGroupInformation.createRemoteUser(impersonationInfo.getPrincipal());
      impersonatedUGI.addCredentials(readCredentials(location));
      return impersonatedUGI;
    } finally {
      try {
        if (!location.delete()) {
          LOG.warn("Failed to delete location: {}", location);
        }
      } catch (IOException e) {
        LOG.warn("Exception raised when deleting location {}", location, e);
      }
    }
  }

  private HttpResponse executeRequest(ImpersonationInfo impersonationInfo) throws IOException {
    HttpRequest request = remoteClient.requestBuilder(HttpMethod.POST, "impersonation/credentials")
      .withBody(GSON.toJson(impersonationInfo))
      .build();
    HttpResponse response = remoteClient.execute(request);
    if (response.getResponseCode() == HttpURLConnection.HTTP_OK) {
      return response;
    }
    throw new IOException(String.format("%s Response: %s.", createErrorMessage(request.getURL()), response));
  }

  // creates error message, encoding details about the request
  private static String createErrorMessage(URL url) {
    return String.format("Error making request to AppFabric Service at %s.", url);
  }

  private static Credentials readCredentials(Location location) throws IOException {
    Credentials credentials = new Credentials();
    try (DataInputStream input = new DataInputStream(new BufferedInputStream(location.getInputStream()))) {
      credentials.readTokenStorageStream(input);
    }
    LOG.debug("Read credentials from {}", location);
    return credentials;
  }
}