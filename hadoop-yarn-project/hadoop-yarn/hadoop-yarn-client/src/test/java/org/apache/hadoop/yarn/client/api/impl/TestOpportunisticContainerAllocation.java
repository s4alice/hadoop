/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.client.api.impl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAccessType;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.ExecutionType;
import org.apache.hadoop.yarn.api.records.ExecutionTypeRequest;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.NMToken;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.api.records.Token;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.ClientRMProxy;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.client.api.NMTokenCache;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.MiniYARNCluster;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.util.Records;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;

/**
 * Class that tests the allocation of OPPORTUNISTIC containers through the
 * centralized ResourceManager.
 */
public class TestOpportunisticContainerAllocation {
  private static Configuration conf = null;
  private static MiniYARNCluster yarnCluster = null;
  private static YarnClient yarnClient = null;
  private static List<NodeReport> nodeReports = null;
  private static ApplicationAttemptId attemptId = null;
  private static int nodeCount = 3;

  private static final int ROLLING_INTERVAL_SEC = 13;
  private static final long AM_EXPIRE_MS = 4000;

  private static Resource capability;
  private static Priority priority;
  private static Priority priority2;
  private static String node;
  private static String rack;
  private static String[] nodes;
  private static String[] racks;
  private final static int DEFAULT_ITERATION = 3;

  @BeforeClass
  public static void setup() throws Exception {
    // start minicluster
    conf = new YarnConfiguration();
    conf.setLong(
        YarnConfiguration.RM_AMRM_TOKEN_MASTER_KEY_ROLLING_INTERVAL_SECS,
        ROLLING_INTERVAL_SEC);
    conf.setLong(YarnConfiguration.RM_AM_EXPIRY_INTERVAL_MS, AM_EXPIRE_MS);
    conf.setInt(YarnConfiguration.RM_NM_HEARTBEAT_INTERVAL_MS, 100);
    // set the minimum allocation so that resource decrease can go under 1024
    conf.setInt(YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_MB, 512);
    conf.setBoolean(
        YarnConfiguration.OPPORTUNISTIC_CONTAINER_ALLOCATION_ENABLED, true);
    conf.setLong(YarnConfiguration.NM_LOG_RETAIN_SECONDS, 1);
    yarnCluster =
        new MiniYARNCluster(TestAMRMClient.class.getName(), nodeCount, 1, 1);
    yarnCluster.init(conf);
    yarnCluster.start();

    // start rm client
    yarnClient = YarnClient.createYarnClient();
    yarnClient.init(conf);
    yarnClient.start();

    // get node info
    nodeReports = yarnClient.getNodeReports(NodeState.RUNNING);

    priority = Priority.newInstance(1);
    priority2 = Priority.newInstance(2);
    capability = Resource.newInstance(1024, 1);

    node = nodeReports.get(0).getNodeId().getHost();
    rack = nodeReports.get(0).getRackName();
    nodes = new String[]{node};
    racks = new String[]{rack};
  }

  @Before
  public void startApp() throws Exception {
    // submit new app
    ApplicationSubmissionContext appContext =
        yarnClient.createApplication().getApplicationSubmissionContext();
    ApplicationId appId = appContext.getApplicationId();
    // set the application name
    appContext.setApplicationName("Test");
    // Set the priority for the application master
    Priority pri = Records.newRecord(Priority.class);
    pri.setPriority(0);
    appContext.setPriority(pri);
    // Set the queue to which this application is to be submitted in the RM
    appContext.setQueue("default");
    // Set up the container launch context for the application master
    ContainerLaunchContext amContainer = BuilderUtils.newContainerLaunchContext(
        Collections.<String, LocalResource>emptyMap(),
        new HashMap<String, String>(), Arrays.asList("sleep", "100"),
        new HashMap<String, ByteBuffer>(), null,
        new HashMap<ApplicationAccessType, String>());
    appContext.setAMContainerSpec(amContainer);
    appContext.setResource(Resource.newInstance(1024, 1));
    // Create the request to send to the applications manager
    SubmitApplicationRequest appRequest =
        Records.newRecord(SubmitApplicationRequest.class);
    appRequest.setApplicationSubmissionContext(appContext);
    // Submit the application to the applications manager
    yarnClient.submitApplication(appContext);

    // wait for app to start
    RMAppAttempt appAttempt = null;
    while (true) {
      ApplicationReport appReport = yarnClient.getApplicationReport(appId);
      if (appReport.getYarnApplicationState() ==
          YarnApplicationState.ACCEPTED) {
        attemptId = appReport.getCurrentApplicationAttemptId();
        appAttempt = yarnCluster.getResourceManager().getRMContext().getRMApps()
            .get(attemptId.getApplicationId()).getCurrentAppAttempt();
        while (true) {
          if (appAttempt.getAppAttemptState() == RMAppAttemptState.LAUNCHED) {
            break;
          }
        }
        break;
      }
    }
    // Just dig into the ResourceManager and get the AMRMToken just for the sake
    // of testing.
    UserGroupInformation.setLoginUser(UserGroupInformation
        .createRemoteUser(UserGroupInformation.getCurrentUser().getUserName()));

    // emulate RM setup of AMRM token in credentials by adding the token
    // *before* setting the token service
    UserGroupInformation.getCurrentUser().addToken(appAttempt.getAMRMToken());
    appAttempt.getAMRMToken()
        .setService(ClientRMProxy.getAMRMTokenService(conf));
  }

  @After
  public void cancelApp() throws YarnException, IOException {
    yarnClient.killApplication(attemptId.getApplicationId());
    attemptId = null;
  }

  @AfterClass
  public static void tearDown() {
    if (yarnClient != null &&
        yarnClient.getServiceState() == Service.STATE.STARTED) {
      yarnClient.stop();
    }
    if (yarnCluster != null &&
        yarnCluster.getServiceState() == Service.STATE.STARTED) {
      yarnCluster.stop();
    }
  }

  @Test(timeout = 60000)
  public void testAMRMClient() throws YarnException, IOException {
    AMRMClient<AMRMClient.ContainerRequest> amClient = null;
    try {
      // start am rm client
      amClient = AMRMClient.<AMRMClient.ContainerRequest>createAMRMClient();

      //setting an instance NMTokenCache
      amClient.setNMTokenCache(new NMTokenCache());
      //asserting we are not using the singleton instance cache
      Assert.assertNotSame(NMTokenCache.getSingleton(),
          amClient.getNMTokenCache());

      amClient.init(conf);
      amClient.start();

      amClient.registerApplicationMaster("Host", 10000, "");

      testAllocation((AMRMClientImpl<AMRMClient.ContainerRequest>)amClient);

      amClient
          .unregisterApplicationMaster(FinalApplicationStatus.SUCCEEDED, null,
              null);

    } finally {
      if (amClient != null &&
          amClient.getServiceState() == Service.STATE.STARTED) {
        amClient.stop();
      }
    }
  }

  private void testAllocation(
      final AMRMClientImpl<AMRMClient.ContainerRequest> amClient)
      throws YarnException, IOException {
    // setup container request

    assertEquals(0, amClient.ask.size());
    assertEquals(0, amClient.release.size());

    amClient.addContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.addContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.addContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.addContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.addContainerRequest(
        new AMRMClient.ContainerRequest(capability, null, null, priority2, 0,
            true, null,
            ExecutionTypeRequest.newInstance(
                ExecutionType.OPPORTUNISTIC, true)));
    amClient.addContainerRequest(
        new AMRMClient.ContainerRequest(capability, null, null, priority2, 0,
            true, null,
            ExecutionTypeRequest.newInstance(
                ExecutionType.OPPORTUNISTIC, true)));

    amClient.removeContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.removeContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.removeContainerRequest(
        new AMRMClient.ContainerRequest(capability, null, null, priority2, 0,
            true, null,
            ExecutionTypeRequest.newInstance(
                ExecutionType.OPPORTUNISTIC, true)));

    int containersRequestedNode = amClient.getTable(0).get(priority,
        node, ExecutionType.GUARANTEED, capability).remoteRequest
        .getNumContainers();
    int containersRequestedRack = amClient.getTable(0).get(priority,
        rack, ExecutionType.GUARANTEED, capability).remoteRequest
        .getNumContainers();
    int containersRequestedAny = amClient.getTable(0).get(priority,
        ResourceRequest.ANY, ExecutionType.GUARANTEED, capability)
        .remoteRequest.getNumContainers();
    int oppContainersRequestedAny =
        amClient.getTable(0).get(priority2, ResourceRequest.ANY,
            ExecutionType.OPPORTUNISTIC, capability).remoteRequest
            .getNumContainers();

    assertEquals(2, containersRequestedNode);
    assertEquals(2, containersRequestedRack);
    assertEquals(2, containersRequestedAny);
    assertEquals(1, oppContainersRequestedAny);

    assertEquals(4, amClient.ask.size());
    assertEquals(0, amClient.release.size());

    // RM should allocate container within 2 calls to allocate()
    int allocatedContainerCount = 0;
    int allocatedOpportContainerCount = 0;
    int iterationsLeft = 10;
    Set<ContainerId> releases = new TreeSet<>();

    amClient.getNMTokenCache().clearCache();
    Assert.assertEquals(0,
        amClient.getNMTokenCache().numberOfTokensInCache());
    HashMap<String, Token> receivedNMTokens = new HashMap<>();

    while (allocatedContainerCount <
        containersRequestedAny + oppContainersRequestedAny
        && iterationsLeft-- > 0) {
      AllocateResponse allocResponse = amClient.allocate(0.1f);
      assertEquals(0, amClient.ask.size());
      assertEquals(0, amClient.release.size());

      allocatedContainerCount += allocResponse.getAllocatedContainers()
          .size();
      for (Container container : allocResponse.getAllocatedContainers()) {
        if (container.getExecutionType() == ExecutionType.OPPORTUNISTIC) {
          allocatedOpportContainerCount++;
        }
        ContainerId rejectContainerId = container.getId();
        releases.add(rejectContainerId);
      }

      for (NMToken token : allocResponse.getNMTokens()) {
        String nodeID = token.getNodeId().toString();
        receivedNMTokens.put(nodeID, token.getToken());
      }

      if (allocatedContainerCount < containersRequestedAny) {
        // sleep to let NM's heartbeat to RM and trigger allocations
        sleep(100);
      }
    }

    assertEquals(allocatedContainerCount,
        containersRequestedAny + oppContainersRequestedAny);
    assertEquals(allocatedOpportContainerCount, oppContainersRequestedAny);
    for (ContainerId rejectContainerId : releases) {
      amClient.releaseAssignedContainer(rejectContainerId);
    }
    assertEquals(3, amClient.release.size());
    assertEquals(0, amClient.ask.size());

    // need to tell the AMRMClient that we don't need these resources anymore
    amClient.removeContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.removeContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority));
    amClient.removeContainerRequest(
        new AMRMClient.ContainerRequest(capability, nodes, racks, priority2, 0,
            true, null,
            ExecutionTypeRequest.newInstance(
                ExecutionType.OPPORTUNISTIC, true)));
    assertEquals(4, amClient.ask.size());

    iterationsLeft = 3;
    // do a few iterations to ensure RM is not going to send new containers
    while (iterationsLeft-- > 0) {
      // inform RM of rejection
      AllocateResponse allocResponse = amClient.allocate(0.1f);
      // RM did not send new containers because AM does not need any
      assertEquals(0, allocResponse.getAllocatedContainers().size());
      if (allocResponse.getCompletedContainersStatuses().size() > 0) {
        for (ContainerStatus cStatus : allocResponse
            .getCompletedContainersStatuses()) {
          if (releases.contains(cStatus.getContainerId())) {
            assertEquals(cStatus.getState(), ContainerState.COMPLETE);
            assertEquals(-100, cStatus.getExitStatus());
            releases.remove(cStatus.getContainerId());
          }
        }
      }
      if (iterationsLeft > 0) {
        // sleep to make sure NM's heartbeat
        sleep(100);
      }
    }
    assertEquals(0, amClient.ask.size());
    assertEquals(0, amClient.release.size());
  }

  private void sleep(int sleepTime) {
    try {
      Thread.sleep(sleepTime);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
