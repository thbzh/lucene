/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionParams.CollectionAction;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Test sync phase that occurs when Leader goes down and a new Leader is
 * elected.
 */
@Slow
@Ignore
public class SyncSliceTest extends SolrCloudBridgeTestCase {
  private boolean success = false;

 // @Override
  public void distribTearDown() throws Exception {
    if (!success) {
    //  printLayoutOnTearDown = true;
    }
   /// super.distribTearDown();
  }

  public SyncSliceTest() {
    super();
    numJettys = TEST_NIGHTLY ? 7 : 4;
    sliceCount = 1;
    replicationFactor = numJettys;
    createControl = true;
  }

  @Test
  public void test() throws Exception {
    
    handle.clear();
    handle.put("timestamp", SKIPVAL);
    
   // waitForThingsToLevelOut(30, TimeUnit.SECONDS);

    List<JettySolrRunner> skipServers = new ArrayList<>();
    int docId = 0;
    indexDoc(skipServers, id, docId++, i1, 50, tlong, 50, t1,
        "to come to the aid of their country.");
    
    indexDoc(skipServers, id, docId++, i1, 50, tlong, 50, t1,
        "old haven was blue.");
     List<Replica> replicas = new ArrayList<>();

   replicas.addAll(cloudClient.getZkStateReader().getClusterState().getCollection(COLLECTION).getSlice("shard1").getReplicas());

    skipServers.add(getJettyOnPort(getReplicaPort(replicas.get(0))));
    
    indexDoc(skipServers, id, docId++, i1, 50, tlong, 50, t1,
        "but the song was fancy.");
    
    skipServers.add(getJettyOnPort(getReplicaPort(replicas.get(1))));
    
    indexDoc(skipServers, id,docId++, i1, 50, tlong, 50, t1,
        "under the moon and over the lake");
    
    commit();
    
   //d waitForRecoveriesToFinish(false);

    // shard should be inconsistent
  //  String shardFailMessage = checkShardConsistency("shard1", true, false);
  //  assertNotNull(shardFailMessage);
    
    ModifiableSolrParams params = new ModifiableSolrParams();
    params.set("action", CollectionAction.SYNCSHARD.toString());
    params.set("collection", "collection1");
    params.set("shard", "shard1");
    SolrRequest request = new QueryRequest(params);
    request.setPath("/admin/collections");
    
    String baseUrl = replicas.get(1).getBaseUrl();
   // baseUrl = baseUrl.substring(0, baseUrl.length() - "collection1".length());
    
    // we only set the connect timeout, not so timeout
    try (HttpSolrClient baseClient = getHttpSolrClient(baseUrl, 10000)) {
      baseClient.request(request);
    }

   // waitForThingsToLevelOut(15, TimeUnit.SECONDS);
    
  //  checkShardConsistency(false, true);
    
    long cloudClientDocs = cloudClient.query(new SolrQuery("*:*")).getResults().getNumFound();
    assertEquals(4, cloudClientDocs);
    
    
    // kill the leader - new leader could have all the docs or be missing one
    JettySolrRunner leaderJetty = getJettyOnPort(getReplicaPort(getShardLeader(COLLECTION, "shard1", 10000)));
    
    skipServers = getRandomOtherJetty(leaderJetty, null); // but not the leader
    
    // this doc won't be on one node
    indexDoc(skipServers, id, docId++, i1, 50, tlong, 50, t1,
        "to come to the aid of their country.");
    commit();
    
    leaderJetty.stop();

    cluster.waitForJettyToStop(leaderJetty);


    cloudClientDocs = cloudClient.query(new SolrQuery("*:*")).getResults().getNumFound();
    assertEquals(5, cloudClientDocs);

    JettySolrRunner deadJetty = leaderJetty;
    
    // let's get the latest leader
    while (deadJetty == leaderJetty) {
   //   updateMappingsFromZk(this.jettys, this.clients);
      leaderJetty = getJettyOnPort(getReplicaPort(getShardLeader(COLLECTION, "shard1", 10000)));
    }
    
    // bring back dead node
    deadJetty.start(); // he is not the leader anymore
    
    cluster.waitForActiveCollection(COLLECTION, 1, numJettys);
    
    skipServers = getRandomOtherJetty(leaderJetty, deadJetty);
    skipServers.addAll( getRandomOtherJetty(leaderJetty, deadJetty));
    // skip list should be 
    
//    System.out.println("leader:" + leaderJetty.url);
//    System.out.println("dead:" + deadJetty.url);
//    System.out.println("skip list:" + skipServers);
    
    // we are skipping  2 nodes
    assertEquals(2, skipServers.size());
    
    // more docs than can peer sync
    for (int i = 0; i < 300; i++) {
      indexDoc(skipServers, id, docId++, i1, 50, tlong, 50, t1,
          "to come to the aid of their country.");
    }
    
    commit();
    
   // waitForRecoveriesToFinish(false);
    
    // shard should be inconsistent
    String shardFailMessage = waitTillInconsistent();
    assertNotNull(
        "Test Setup Failure: shard1 should have just been set up to be inconsistent - but it's still consistent. Leader:"
            + leaderJetty.getBaseUrl() + " Dead Guy:" + deadJetty.getBaseUrl() + "skip list:" + skipServers, shardFailMessage);
    
    // good place to test compareResults
    boolean shouldFail = CloudInspectUtil.compareResults(controlClient, cloudClient);
    assertTrue("A test that compareResults is working correctly failed", shouldFail);

    
    // kill the current leader
    leaderJetty.stop();

    cluster.waitForJettyToStop(leaderJetty);
    
    //waitForNoShardInconsistency();

    //checkShardConsistency(true, true);
    
    success = true;
  }

  private void waitTillAllNodesActive() throws Exception {
    for (int i = 0; i < 60; i++) { 
      Thread.sleep(3000);
      ZkStateReader zkStateReader = cloudClient.getZkStateReader();
      ClusterState clusterState = zkStateReader.getClusterState();
      DocCollection collection1 = clusterState.getCollection("collection1");
      Slice slice = collection1.getSlice("shard1");
      Collection<Replica> replicas = slice.getReplicas();
      boolean allActive = true;
      for (Replica replica : replicas) {
        if (!clusterState.liveNodesContain(replica.getNodeName()) || replica.getState() != Replica.State.ACTIVE) {
          allActive = false;
          break;
        }
      }
      if (allActive) {
        return;
      }
    }

    fail("timeout waiting to see all nodes active");
  }

  private String waitTillInconsistent() throws Exception, InterruptedException {
    String shardFailMessage = null;
    
    shardFailMessage = pollConsistency(shardFailMessage, 0);
    shardFailMessage = pollConsistency(shardFailMessage, 3000);
    shardFailMessage = pollConsistency(shardFailMessage, 5000);
    shardFailMessage = pollConsistency(shardFailMessage, 15000);
    
    return shardFailMessage;
  }

  private String pollConsistency(String shardFailMessage, int sleep)
      throws InterruptedException, Exception {
    try {
      commit();
    } catch (Throwable t) {
      t.printStackTrace();
    }
    if (shardFailMessage == null) {
      // try again
      Thread.sleep(sleep);
     // shardFailMessage = checkShardConsistency("shard1", true, false);
    }
    return shardFailMessage;
  }
  
  private List<JettySolrRunner> getRandomOtherJetty(JettySolrRunner leader, JettySolrRunner down) {
    List<JettySolrRunner> skipServers = new ArrayList<>();
    List<JettySolrRunner> candidates = new ArrayList<>();
    candidates.addAll(cluster.getJettySolrRunners());

    if (leader != null) {
      candidates.remove(leader);
    }
    
    if (down != null) {
      candidates.remove(down);
    }

    JettySolrRunner cjetty = candidates.get(random().nextInt(candidates.size()));
    skipServers.add(cjetty);
    return skipServers;
  }
  
  protected void indexDoc(List<JettySolrRunner> skipServers, Object... fields) throws IOException,
      SolrServerException {
    SolrInputDocument doc = new SolrInputDocument();
    
    addFields(doc, fields);
    addFields(doc, "rnd_b", true);
    
    controlClient.add(doc);
    
    UpdateRequest ureq = new UpdateRequest();
    ureq.add(doc);
    ModifiableSolrParams params = new ModifiableSolrParams();
    for (JettySolrRunner skip : skipServers) {
      params.add("test.distrib.skip.servers", skip.getBaseUrl() + "/");
    }
    ureq.setParams(params);
    ureq.process(cloudClient);
  }

}
