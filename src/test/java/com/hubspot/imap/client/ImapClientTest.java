package com.hubspot.imap.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.SingleBody;
import org.apache.james.mime4j.dom.TextBody;
import org.assertj.core.api.Condition;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.hubspot.imap.ImapMultiServerTest;
import com.hubspot.imap.TestServerConfig;
import com.hubspot.imap.TestUtils;
import com.hubspot.imap.protocol.command.ImapCommandType;
import com.hubspot.imap.protocol.command.SilentStoreCommand;
import com.hubspot.imap.protocol.command.StoreCommand.StoreAction;
import com.hubspot.imap.protocol.command.fetch.UidCommand;
import com.hubspot.imap.protocol.command.fetch.items.BodyPeekFetchDataItem;
import com.hubspot.imap.protocol.command.fetch.items.FetchDataItem.FetchDataItemType;
import com.hubspot.imap.protocol.command.search.DateSearches;
import com.hubspot.imap.protocol.command.search.SearchCommand;
import com.hubspot.imap.protocol.command.search.keys.AllSearchKey;
import com.hubspot.imap.protocol.command.search.keys.UidSearchKey;
import com.hubspot.imap.protocol.exceptions.UnknownFetchItemTypeException;
import com.hubspot.imap.protocol.folder.FolderMetadata;
import com.hubspot.imap.protocol.message.Envelope;
import com.hubspot.imap.protocol.message.ImapMessage;
import com.hubspot.imap.protocol.message.MessageFlag;
import com.hubspot.imap.protocol.message.StandardMessageFlag;
import com.hubspot.imap.protocol.message.UnfetchedFieldException;
import com.hubspot.imap.protocol.response.ResponseCode;
import com.hubspot.imap.protocol.response.tagged.FetchResponse;
import com.hubspot.imap.protocol.response.tagged.ListResponse;
import com.hubspot.imap.protocol.response.tagged.NoopResponse;
import com.hubspot.imap.protocol.response.tagged.OpenResponse;
import com.hubspot.imap.protocol.response.tagged.SearchResponse;
import com.hubspot.imap.protocol.response.tagged.StreamingFetchResponse;
import com.hubspot.imap.protocol.response.tagged.TaggedResponse;

import io.netty.util.concurrent.Future;

@RunWith(Parameterized.class)
public class ImapClientTest extends ImapMultiServerTest {
  private static final ZonedDateTime JULY_19_2015 = ZonedDateTime.of(2015, 7, 19, 0, 0, 0, 0,
      TimeZone.getTimeZone("EST").toZoneId());
  private static final ZonedDateTime JULY_1_2015 = ZonedDateTime.of(2015, 7, 1, 0, 0, 0, 0,
      TimeZone.getTimeZone("EST").toZoneId());

  private static Map<TestServerConfig, ImapClient> clients = new HashMap<>();
  private static Map<TestServerConfig, OpenResponse> allFolderOpenResponses = new HashMap<>();
  private static Map<TestServerConfig, List<ImapMessage>> allMessagesMap = new HashMap<>();

  private static final long FETCH_TIMEOUT_SECS = 30;

  @Parameter public TestServerConfig testServerConfig;
  private ImapClient client;
  private OpenResponse allFolderOpenResponse;
  private List<ImapMessage> allMessages;

  @BeforeClass
  public static void prefetch() throws Exception {
    for (TestServerConfig config : parameters()) {
      ImapClient profileClient = getLoggedInClient(config);
      clients.put(config, profileClient);

      OpenResponse allMailOpenResponse = profileClient.open(config.primaryFolder(), FolderOpenMode.WRITE)
                                                      .get();
      assertThat(allMailOpenResponse.getCode()).isEqualTo(ResponseCode.OK);
      allFolderOpenResponses.put(config, allMailOpenResponse);

      allMessagesMap.put(config,
          TestUtils.fetchMessages(profileClient, profileClient.uidsearch(allEmailSearchCommand()).get().getMessageIds()));
    }
  }

  public static SearchCommand allEmailSearchCommand() {
    return new SearchCommand(new AllSearchKey());
  }

  @AfterClass
  public static void cleanup() throws Exception {
    clients.forEach((profile, client) -> {
      if (client != null && client.isLoggedIn()) {
        client.close();
      }
    });
  }

  @Before
  public void initialize() {
    client = clients.get(testServerConfig);
    allFolderOpenResponse = allFolderOpenResponses.get(testServerConfig);
    allMessages = allMessagesMap.get(testServerConfig);
  }

  @Test
  public void testLogin_doesAuthenticateConnection() throws Exception {
    Future<NoopResponse> noopResponseFuture = client.noop();
    NoopResponse taggedResponse = noopResponseFuture.get();

    assertThat(taggedResponse.getCode()).isEqualTo(ResponseCode.OK);
  }

  @Test
  public void testCapability_doesGetResponse() throws Exception {
    Future<TaggedResponse> capabilityResponseFuture = client.send(ImapCommandType.CAPABILITY);
    TaggedResponse taggedResponse = capabilityResponseFuture.get();

    assertThat(taggedResponse.getCode()).isEqualTo(ResponseCode.OK);
  }

  @Test
  public void testList_doesReturnFolders() throws Exception {
    Future<ListResponse> listResponseFuture = client.list("", "*");

    ListResponse response = listResponseFuture.get();

    assertThat(response.getCode()).isEqualTo(ResponseCode.OK);
    assertThat(response.getFolders().size()).isGreaterThan(0);
    assertThat(response.getFolders()).have(new Condition<>(m -> m.getAttributes().size() > 0, "attributes"));
    assertThat(response.getFolders()).extracting(FolderMetadata::getName)
                                     .contains(testServerConfig.primaryFolder());
  }

  @Test
  public void testGivenFolderName_canOpenFolder() throws Exception {
    Future<OpenResponse> responseFuture = client.open(testServerConfig.primaryFolder(), FolderOpenMode.WRITE);
    OpenResponse response = responseFuture.get();

    assertThat(response.getCode()).isEqualTo(ResponseCode.OK);
    assertThat(response.getExists()).isGreaterThan(0);
    assertThat(response.getFlags().size()).isGreaterThan(0);
    assertThat(response.getPermanentFlags().size()).isGreaterThan(0);
    assertThat(response.getUidValidity()).isGreaterThan(0);
    assertThat(response.getRecent()).isEqualTo(0);
    assertThat(response.getUidNext()).isGreaterThan(0);
  }

  @Test
  public void testFetch_doesReturnMessages() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(2L), FetchDataItemType.FAST);
    FetchResponse response = responseFuture.get();

    assertThat(response.getCode()).isEqualTo(ResponseCode.OK);
    assertThat(response.getMessages().size()).isGreaterThan(0);
    assertThat(response.getMessages().size()).isEqualTo(2);

    assertThat(response.getMessages()).have(new Condition<>(m -> {
      try {
        return m.getSize() > 0;
      } catch (UnfetchedFieldException e) {
        return false;
      }
    }, "size"));

    assertThat(response.getMessages()).have(new Condition<>(m -> {
      try {
        return m.getInternalDate().isAfter(ZonedDateTime.of(0, 1, 1, 1, 1, 1, 1, ZoneId.of("UTC")));
      } catch (UnfetchedFieldException e) {
        return false;
      }
    }, "internaldate"));
  }

  @Test(expected = UnfetchedFieldException.class)
  public void testAccessingUnfetchedField_doesThrowException() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(2L), FetchDataItemType.FLAGS);
    FetchResponse response = responseFuture.get();

    assertThat(response.getCode()).isEqualTo(ResponseCode.OK);
    assertThat(response.getMessages().size()).isGreaterThan(0);
    response.getMessages().iterator().next().getInternalDate();
  }

  @Test
  public void testFetchUid_doesGetUid() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(2L), FetchDataItemType.UID);
    FetchResponse response = responseFuture.get();

    assertThat(response.getCode()).isEqualTo(ResponseCode.OK);

    assertThat(response.getMessages().size()).isGreaterThan(0);
    assertThat(response.getMessages().iterator().next().getUid()).isGreaterThan(0);
  }

  @Test
  public void testFetchEnvelope_doesFetchEnvelope() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(3, Optional.of(4L), FetchDataItemType.ENVELOPE);
    FetchResponse response = responseFuture.get();

    assertThat(response.getCode()).isEqualTo(ResponseCode.OK);

    assertThat(response.getMessages().size()).isGreaterThan(0);

    Envelope envelope = response.getMessages().iterator().next().getEnvelope();
    assertThat(envelope.getDate()).isNotNull();
    assertThat(envelope.getFrom().size()).isEqualTo(1);
    assertThat(envelope.getTo().size()).isGreaterThanOrEqualTo(1);
    assertThat(envelope.getSubject()).isNotEmpty();
    assertThat(envelope.getMessageId()).isNotEmpty();
  }

  @Test
  public void testFetchBody_doesThrowUnknownFetchItemException() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(2L), FetchDataItemType.BODY);

    try {
      responseFuture.get();
    } catch (ExecutionException e) {
      assertThat(e.getCause()).hasCauseInstanceOf(UnknownFetchItemTypeException.class);
    }
  }

  @Test
  public void testFetchBodyHeaders_doesParseHeaders() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(10L), new BodyPeekFetchDataItem("HEADER"));
    FetchResponse response = responseFuture.get();
    assertThat(response.getMessages()).have(new Condition<>(m -> {
      try {
        return !Strings.isNullOrEmpty(m.getBody().getHeader().getField("Message-ID").getBody());
      } catch (UnfetchedFieldException e) {
        throw Throwables.propagate(e);
      }
    }, "message id"));

    responseFuture.get();
  }

  @Test
  public void testFetchBody_doesFetchTextBody() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(2L), new BodyPeekFetchDataItem());
    FetchResponse response = responseFuture.get();
    assertThat(response.getMessages()).have(new Condition<>(m -> {
      try {
        SingleBody textBody = null;
        String charset = null;

        if (m.getBody().isMultipart()) {
          Multipart multipart = (Multipart) m.getBody().getBody();

          boolean hasTextBody = false;
          for (Entity entity : multipart.getBodyParts()) {
            if (entity.getMimeType().equalsIgnoreCase("text/plain")) {
              textBody = (SingleBody) entity.getBody();
              charset = entity.getCharset();
              hasTextBody = true;
            }
          }

          if (!hasTextBody) {
            return false;
          }
        } else {
          if (!(m.getBody().getBody() instanceof TextBody)) {
            return false;
          }
          textBody = (TextBody) m.getBody().getBody();
          charset = m.getBody().getCharset();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        textBody.writeTo(baos);
        return !Strings.isNullOrEmpty(baos.toString(charset));
      } catch (UnfetchedFieldException | IOException e) {
        throw Throwables.propagate(e);
      }
    }, "text body"));

    responseFuture.get();
  }

  @Test
  public void testUidFetch() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(1, Optional.of(2L), FetchDataItemType.UID, FetchDataItemType.ENVELOPE);
    FetchResponse response = responseFuture.get();

    ImapMessage message = response.getMessages().iterator().next();

    Future<FetchResponse> uidfetchFuture = client.uidfetch(message.getUid(), Optional.of(message.getUid()), FetchDataItemType.UID,
        FetchDataItemType.ENVELOPE);
    FetchResponse uidresponse = uidfetchFuture.get();

    assertThat(uidresponse.getMessages().size()).isEqualTo(1);
    assertThat(uidresponse.getMessages().iterator().next().getUid()).isEqualTo(message.getUid());
  }

  @Test
  public void testStreamingFetch_doesExecuteConsumerForAllMessages() throws Exception {
    Set<Long> uids = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Future<StreamingFetchResponse<Void>> fetchResponseFuture = client.fetch(1, Optional.empty(), message -> {
      try {
        uids.add(message.getUid());
      } catch (UnfetchedFieldException e) {
        throw Throwables.propagate(e);
      }

      return (Void) null;
    }, FetchDataItemType.UID);

    StreamingFetchResponse<Void> fetchResponse = fetchResponseFuture.get();

    int successful = 0;
    for (Future consumerFuture : fetchResponse.getMessageConsumerFutures()) {
      consumerFuture.get();
      successful++;
    }

    assertThat(successful).isEqualTo(uids.size());
  }

  @Test
  public void testStore() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(allFolderOpenResponse.getExists(), Optional.<Long>empty(),
        FetchDataItemType.FLAGS, FetchDataItemType.UID);
    FetchResponse fetchResponse = responseFuture.get();
    ImapMessage message = fetchResponse.getMessages().iterator().next();

    TaggedResponse storeResponse = client.send(new UidCommand(
        ImapCommandType.STORE,
        new SilentStoreCommand(StoreAction.ADD_FLAGS, message.getUid(), message.getUid(), StandardMessageFlag.FLAGGED)
    )).get();

    assertThat(storeResponse.getCode()).isEqualTo(ResponseCode.OK);

    responseFuture = client.fetch(allFolderOpenResponse.getExists(), Optional.<Long>empty(), FetchDataItemType.FLAGS,
        FetchDataItemType.UID);
    fetchResponse = responseFuture.get();
    ImapMessage messageWithFlagged = fetchResponse.getMessages().iterator().next();

    assertThat(messageWithFlagged.getFlags()).contains(StandardMessageFlag.FLAGGED);

    storeResponse = client.send(new UidCommand(
        ImapCommandType.STORE,
        new SilentStoreCommand(StoreAction.REMOVE_FLAGS, message.getUid(), message.getUid(), StandardMessageFlag.FLAGGED)
    )).get();

    assertThat(storeResponse.getCode()).isEqualTo(ResponseCode.OK);

    responseFuture = client.fetch(allFolderOpenResponse.getExists(), Optional.<Long>empty(), FetchDataItemType.FLAGS,
        FetchDataItemType.UID);
    fetchResponse = responseFuture.get();
    ImapMessage messageNotFlagged = fetchResponse.getMessages().iterator().next();

    assertThat(messageNotFlagged.getFlags().stream()
                                .map(MessageFlag::getString)
                                .collect(Collectors.toList()))
        .containsOnlyElementsOf(message.getFlags().stream()
                                       .map(MessageFlag::getString)
                                       .collect(Collectors.toList()));
  }

  @Test
  public void testSimpleSearch() throws Exception {
    Future<FetchResponse> responseFuture = client.fetch(allFolderOpenResponse.getExists() - 2, Optional.<Long>empty(),
        FetchDataItemType.FLAGS, FetchDataItemType.UID);
    FetchResponse fetchResponse = responseFuture.get();
    ImapMessage message = fetchResponse.getMessages()
                                       .stream()
                                       .collect(Collectors.minBy(Comparator.comparing(TestUtils::msgToUid)))
                                       .get();

    SearchResponse response = client.search(
        new UidSearchKey(String.valueOf(message.getUid()) + ":" + allFolderOpenResponse.getUidNext())).get();
    assertThat(response.getMessageIds().size()).isEqualTo(fetchResponse.getMessages().size());

    List<Long> expectedUids = fetchResponse.getMessages().stream().map(TestUtils::msgToUid).collect(Collectors.toList());

    Set<ImapMessage> responseMessages = new HashSet<>();
    for (long messasgeId : response.getMessageIds()) {
      responseMessages.addAll(
          client.fetch(messasgeId, Optional.empty(), FetchDataItemType.FLAGS, FetchDataItemType.UID).get().getMessages());
    }

    assertThat(responseMessages.stream()
                               .map(TestUtils::msgToUid)
                               .collect(Collectors.toList()))
        .containsOnlyElementsOf(expectedUids);
  }

  @Test
  public void testSearchBefore_returnsAllEmailsBeforeDate() throws Exception {
    ZonedDateTime end = JULY_19_2015;

    List<ImapMessage> allMessagesBeforeEnd = allMessages.stream()
                                                        .filter(msg -> TestUtils.msgToInternalDate(msg).isBefore(end))
                                                        .collect(Collectors.toList());

    SearchResponse searchResponse = client.uidsearch(DateSearches.searchBefore(end)).get();
    assertThat(searchResponse.getCode()).isEqualTo(ResponseCode.OK);

    List<ImapMessage> messagesBeforeEnd = TestUtils.fetchMessages(client, searchResponse.getMessageIds());

    assertThat(TestUtils.msgsToUids(messagesBeforeEnd)).containsOnlyElementsOf(TestUtils.msgsToUids(allMessagesBeforeEnd));
  }

  @Test
  public void testSearchAfter_returnsAllEmailsAfterDate() throws Exception {
    ZonedDateTime start = JULY_19_2015;

    List<ImapMessage> allMessagesAfterStart = allMessages.stream()
                                                         .filter(msg -> TestUtils.msgToInternalDate(msg).isAfter(start))
                                                         .collect(Collectors.toList());

    SearchResponse searchResponse = client.uidsearch(DateSearches.searchAfter(start)).get();
    assertThat(searchResponse.getCode()).isEqualTo(ResponseCode.OK);

    List<ImapMessage> messagesAfterStart = TestUtils.fetchMessages(client, searchResponse.getMessageIds());
    assertThat(TestUtils.msgsToUids(messagesAfterStart)).containsOnlyElementsOf(TestUtils.msgsToUids(allMessagesAfterStart));
  }

  @Test
  public void testSearchBetween_returnsAllEmailsInRange() throws Exception {
    ZonedDateTime start = JULY_1_2015;
    ZonedDateTime end = JULY_19_2015;

    List<ImapMessage> allMessagesInRange = allMessages.stream()
                                                      .filter(msg -> TestUtils.msgToInternalDate(msg).isAfter(start)
                                                          && TestUtils.msgToInternalDate(msg).isBefore(end))
                                                      .collect(Collectors.toList());

    SearchResponse searchResponse = client.uidsearch(DateSearches.searchBetween(start, end)).get();
    assertThat(searchResponse.getCode()).isEqualTo(ResponseCode.OK);

    List<ImapMessage> messagesInRange = TestUtils.fetchMessages(client, searchResponse.getMessageIds());
    assertThat(TestUtils.msgsToUids(messagesInRange)).containsOnlyElementsOf(TestUtils.msgsToUids(allMessagesInRange));
  }

  @Test
  public void testSetFetch_returnsOnlyEmailsInSet() throws Exception {
    assertThat(allMessages.size()).isGreaterThan(4);
    Iterable<ImapMessage> emailsWithSkips = Iterables.concat(allMessages.subList(0, 2), allMessages.subList(3, 4));

    Set<Long> fetchSet = new HashSet<>();
    emailsWithSkips.forEach(email -> fetchSet.add(TestUtils.msgToUid(email)));

    FetchResponse fetchResponse = client.uidfetch(fetchSet, FetchDataItemType.UID, FetchDataItemType.ENVELOPE,
        FetchDataItemType.INTERNALDATE).get(FETCH_TIMEOUT_SECS, TimeUnit.SECONDS);
    Set<ImapMessage> messages = fetchResponse.getMessages();
    assertThat(TestUtils.msgsToUids(messages)).containsOnlyElementsOf(fetchSet);
  }
}
