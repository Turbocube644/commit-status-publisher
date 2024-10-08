/*
 * Copyright 2000-2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.commitPublisher.gitea;

import com.google.gson.Gson;
import jetbrains.buildServer.MockBuildPromotion;
import jetbrains.buildServer.commitPublisher.*;
import jetbrains.buildServer.commitPublisher.gitea.data.GiteaCommitStatus;
import jetbrains.buildServer.commitPublisher.gitea.data.GiteaPermissions;
import jetbrains.buildServer.commitPublisher.gitea.data.GiteaRepoInfo;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.vcs.VcsRootInstance;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.entity.StringEntity;
import org.jetbrains.annotations.NotNull;
import org.jmock.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author Felix Heim, 21/02/22.
 */
@Test
public class GiteaPublisherTest extends HttpPublisherTest {

  private static final String GROUP_REPO = "group_repo";
  private final Map<String, List<GiteaCommitStatus>> myRevisionToStatuses = new HashMap<>();

  public GiteaPublisherTest() {
    myExpectedRegExps.put(EventToTest.QUEUED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*pending.*%s.*", REVISION, DefaultStatusMessages.BUILD_QUEUED));
    myExpectedRegExps.put(EventToTest.REMOVED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*failure.*%s\".*", REVISION, DefaultStatusMessages.BUILD_REMOVED_FROM_QUEUE));
    myExpectedRegExps.put(EventToTest.STARTED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*pending.*%s.*", REVISION, DefaultStatusMessages.BUILD_STARTED));
    myExpectedRegExps.put(EventToTest.FINISHED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*success.*Success.*", REVISION));
    myExpectedRegExps.put(EventToTest.FAILED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*failure.*Failure.*", REVISION));
    myExpectedRegExps.put(EventToTest.COMMENTED_SUCCESS, null); // not to be tested
    myExpectedRegExps.put(EventToTest.COMMENTED_FAILED, null); // not to be tested
    myExpectedRegExps.put(EventToTest.COMMENTED_INPROGRESS, null); // not to be tested
    myExpectedRegExps.put(EventToTest.COMMENTED_INPROGRESS_FAILED, null); // not to be tested
    myExpectedRegExps.put(EventToTest.INTERRUPTED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*failure.*%s.*", REVISION, PROBLEM_DESCR));
    myExpectedRegExps.put(EventToTest.FAILURE_DETECTED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*failure.*%s.*", REVISION, PROBLEM_DESCR));
    myExpectedRegExps.put(EventToTest.MARKED_SUCCESSFUL, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*success.*%s.*", REVISION, DefaultStatusMessages.BUILD_MARKED_SUCCESSFULL));
    myExpectedRegExps.put(EventToTest.MARKED_RUNNING_SUCCESSFUL, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*pending.*%s.*", REVISION, DefaultStatusMessages.BUILD_MARKED_SUCCESSFULL));
    myExpectedRegExps.put(EventToTest.TEST_CONNECTION, ".*/repos/owner/project.*");
    myExpectedRegExps.put(EventToTest.PAYLOAD_ESCAPED, String.format(".*/repos/owner/project/statuses/%s.*ENTITY:.*failure.*%s.*Failure.*", REVISION, BT_NAME_ESCAPED_REGEXP));
  }

  public void test_buildFinishedSuccessfully_server_url_with_subdir() throws Exception {
    Map<String, String> params = getPublisherParams();
    setExpectedApiPath("/subdir/api/v1");
    params.put(Constants.GITEA_API_URL, getServerUrl() + "/subdir/api/v1");
    myVcsRoot.setProperties(Collections.singletonMap("url", "https://url.com/subdir/owner/project"));
    VcsRootInstance vcsRootInstance = myBuildType.getVcsRootInstanceForParent(myVcsRoot);
    myRevision = new BuildRevision(vcsRootInstance, REVISION, "", REVISION);
    myPublisher = new GiteaPublisher(myPublisherSettings, myBuildType, FEATURE_ID, params, myProblems, myWebLinks, new CommitStatusesCache<>());
    test_buildFinished_Successfully();
  }

  public void test_buildFinishedSuccessfully_server_url_with_slash() throws Exception {
    Map<String, String> params = getPublisherParams();
    setExpectedApiPath("/subdir/api/v1");
    params.put(Constants.GITEA_API_URL, getServerUrl() + "/subdir/api/v1/");
    myVcsRoot.setProperties(Collections.singletonMap("url", "https://url.com/subdir/owner/project"));
    VcsRootInstance vcsRootInstance = myBuildType.getVcsRootInstanceForParent(myVcsRoot);
    myRevision = new BuildRevision(vcsRootInstance, REVISION, "", REVISION);
    myPublisher = new GiteaPublisher(myPublisherSettings, myBuildType, FEATURE_ID, params, myProblems, myWebLinks, new CommitStatusesCache<>());
    test_buildFinished_Successfully();
  }

  public void should_fail_with_error_on_wrong_vcs_url() throws InterruptedException {
    myVcsRoot.setProperties(Collections.singletonMap("url", "wrong://url.com"));
    VcsRootInstance vcsRootInstance = myBuildType.getVcsRootInstanceForParent(myVcsRoot);
    BuildRevision revision = new BuildRevision(vcsRootInstance, REVISION, "", REVISION);
    try {
      myPublisher.buildFinished(myFixture.createBuild(myBuildType, Status.NORMAL), revision);
      fail("PublishError exception expected");
    } catch(PublisherException ex) {
      then(ex.getMessage()).matches("Cannot parse.*" + myVcsRoot.getName() + ".*");
    }
  }

  public void should_work_with_dots_in_id() throws PublisherException, InterruptedException {
    myVcsRoot.setProperties(Collections.singletonMap("url", "https://url.com/own.er/pro.ject"));
    VcsRootInstance vcsRootInstance = myBuildType.getVcsRootInstanceForParent(myVcsRoot);
    BuildRevision revision = new BuildRevision(vcsRootInstance, REVISION, "", REVISION);
    setExpectedEndpointPrefix("/repos/own%2Eer/pro%2Eject");
    myPublisher.buildFinished(myFixture.createBuild(myBuildType, Status.NORMAL), revision);
    then(getRequestAsString()).isNotNull().doesNotMatch(".*error.*")
                          .matches(String.format(".*/repos/own%%2Eer/pro%%2Eject/statuses/%s.*ENTITY:.*success.*Success.*", REVISION));
  }

  public void test_testConnection_group_repo() throws Exception {
    if (!myPublisherSettings.isTestConnectionSupported()) return;
    Map<String, String> params = getPublisherParams();
    myVcsRoot.setProperties(Collections.singletonMap("url", getServerUrl()  + "/" + OWNER + "/" + GROUP_REPO));
    myPublisherSettings.testConnection(myBuildType, myVcsRoot, params);
    then(getRequestAsString()).isNotNull()
                          .doesNotMatch(".*error.*")
                          .matches(".*/repos/owner/group_repo.*");
  }

  public void should_calculate_correct_revision_status() {
    BuildPromotion promotion = new MockBuildPromotion();
    GiteaPublisher publisher = (GiteaPublisher)myPublisher;
    assertNull(publisher.getRevisionStatus(promotion, (GiteaCommitStatus)null));
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, null, null, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, "nonsense", null, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.SUCCESS.getName(), null, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.FAILURE.getName(), null, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.ERROR.getName(), null, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), DefaultStatusMessages.BUILD_MARKED_SUCCESSFULL, null, null)).getTriggeredEvent());
    assertEquals(CommitStatusPublisher.Event.QUEUED, publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), null, null, null)).getTriggeredEvent());
    assertEquals(CommitStatusPublisher.Event.STARTED, publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), DefaultStatusMessages.BUILD_STARTED, null, null)).getTriggeredEvent());
    assertEquals(CommitStatusPublisher.Event.REMOVED_FROM_QUEUE, publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.FAILURE.getName(), DefaultStatusMessages.BUILD_REMOVED_FROM_QUEUE, null, null)).getTriggeredEvent());
    assertEquals(CommitStatusPublisher.Event.REMOVED_FROM_QUEUE, publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.FAILURE.getName(), DefaultStatusMessages.BUILD_REMOVED_FROM_QUEUE_AS_CANCELED, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), "", null, null)).getTriggeredEvent());
    assertEquals(CommitStatusPublisher.Event.QUEUED, publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), DefaultStatusMessages.BUILD_QUEUED, null, null)).getTriggeredEvent());
    assertNull(publisher.getRevisionStatus(promotion, new GiteaCommitStatus(null, GiteaBuildStatus.FAILURE.getName(), null, null, null)).getTriggeredEvent());
  }

  public void should_allow_queued_depending_on_build_type() {
    Mock removedBuildMock = new Mock(SQueuedBuild.class);
    removedBuildMock.stubs().method("getBuildTypeId").withNoArguments().will(returnValue("buildType"));
    removedBuildMock.stubs().method("getItemId").withNoArguments().will(returnValue("123"));
    Mock buildPromotionMock = new Mock(BuildPromotion.class);
    Mock buildTypeMock = new Mock(SBuildType.class);
    buildTypeMock.stubs().method("getFullName").withNoArguments().will(returnValue("typeFullName"));
    buildPromotionMock.stubs().method("getBuildType").withNoArguments().will(returnValue(buildTypeMock.proxy()));
    removedBuildMock.stubs().method("getBuildPromotion").withNoArguments().will(returnValue(buildPromotionMock.proxy()));
    SQueuedBuild removedBuild = (SQueuedBuild)removedBuildMock.proxy();

    GiteaPublisher publisher = (GiteaPublisher)myPublisher;
    assertTrue(publisher.getRevisionStatusForRemovedBuild(removedBuild, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), DefaultStatusMessages.BUILD_QUEUED, "typeFullName", "http://localhost:8111/viewQueued.html?itemId=123")).isEventAllowed(CommitStatusPublisher.Event.REMOVED_FROM_QUEUE, Long.MAX_VALUE));
    assertFalse(publisher.getRevisionStatusForRemovedBuild(removedBuild, new GiteaCommitStatus(null, GiteaBuildStatus.PENDING.getName(), DefaultStatusMessages.BUILD_QUEUED, "anotherTypeFullName", "http://localhost:8111/viewQueued.html?itemId=321")).isEventAllowed(CommitStatusPublisher.Event.REMOVED_FROM_QUEUE, Long.MAX_VALUE));
  }

  @BeforeMethod
  @Override
  protected void setUp() throws Exception {
    setExpectedApiPath("/api/v1");
    setExpectedEndpointPrefix("/repos/" + OWNER + "/" + CORRECT_REPO);
    super.setUp();
    myPublisherSettings = new GiteaSettings(new MockPluginDescriptor(), myWebLinks, myProblems, myTrustStoreProvider);
    Map<String, String> params = getPublisherParams();
    myPublisher = new GiteaPublisher(myPublisherSettings, myBuildType, FEATURE_ID, params, myProblems, myWebLinks, new CommitStatusesCache<>());
    myBuildType.getProject().addParameter(new SimpleParameter("teamcity.commitStatusPublisher.publishQueuedBuildStatus", "true"));
  }

  @Override
  protected Map<String, String> getPublisherParams() {
    return new HashMap<String, String>() {{
      put(Constants.GITEA_TOKEN, "TOKEN");
      put(Constants.GITEA_API_URL, getServerUrl() + getExpectedApiPath());
    }};
  }

  @Override
  protected boolean respondToGet(String url, HttpResponse httpResponse) {
    if (url.contains("/repos/" + OWNER + "/" + CORRECT_REPO + "/statuses")) {
      String revision = getRevision(url, "/api/v1/repos/" + OWNER + "/" + CORRECT_REPO + "/statuses/");
      respondWithStatuses(httpResponse, revision);
    } else if (url.contains("/repos" +  "/" + OWNER + "/" + CORRECT_REPO)) {
      respondWithRepoInfo(httpResponse, CORRECT_REPO, false, true);
    } else if (url.contains("/repos"  + "/" + OWNER + "/" +  GROUP_REPO)) {
      respondWithRepoInfo(httpResponse, GROUP_REPO, true, true);
    } else if (url.contains("/repos"  + "/" + OWNER + "/" +  READ_ONLY_REPO)) {
      respondWithRepoInfo(httpResponse, READ_ONLY_REPO, false, false);
    } else {
      respondWithError(httpResponse, 404, String.format("Unexpected URL: %s", url));
      return false;
    }
    return true;
  }

  private void respondWithStatuses(HttpResponse httpResponse, String revision) {
    List<GiteaCommitStatus> statuses = myRevisionToStatuses.getOrDefault(revision, new ArrayList<>());
    String json = gson.toJson(statuses.stream().map(s -> new GiteaCommitStatus(0L, s.status, s.description, s.context, s.target_url)).collect(Collectors.toList()));
    httpResponse.setEntity(new StringEntity(json, StandardCharsets.UTF_8));
  }

  @Override
  protected boolean respondToPost(String url, String requestData, final HttpRequest httpRequest, HttpResponse httpResponse) {
    String revision = getRevision(url, "/api/v1/repos/" + OWNER + "/" + CORRECT_REPO + "/statuses/");
    if (revision != null) {
      GiteaCommitStatus status = gson.fromJson(requestData, GiteaCommitStatus.class);
      myRevisionToStatuses.computeIfAbsent(revision, k -> new ArrayList<>()).add(status);
    }
    return isUrlExpected(url, httpResponse);
  }


  private void respondWithRepoInfo(HttpResponse httpResponse, String repoName, boolean isGroupRepo, boolean isPushPermitted) {
    Gson gson = new Gson();
    GiteaRepoInfo repoInfo = new GiteaRepoInfo();
    repoInfo.id = "111";
    repoInfo.permissions = new GiteaPermissions();
    repoInfo.permissions.push = isPushPermitted;
    String jsonResponse = gson.toJson(repoInfo);
    httpResponse.setEntity(new StringEntity(jsonResponse, "UTF-8"));
  }

  @Override
  protected boolean checkEventFinished(@NotNull String requestString, boolean isSuccessful) {
    return requestString.contains(isSuccessful ? "success" : "failure");
  }
}
