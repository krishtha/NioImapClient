package com.hubspot.imap.profiles;

import java.io.IOException;

import org.assertj.core.util.Strings;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.common.base.Throwables;
import com.hubspot.imap.ImapClientFactory;
import com.hubspot.imap.ImapConfiguration;
import com.hubspot.imap.ImapConfigurationIF.AuthType;
import com.hubspot.imap.utils.ImapServerDetails;

public class GmailOAuthProfile extends GmailProfile {

  private static final String APP_ID = null;
  private static final String APP_SECRET = null;
  private static final String REFRESH_TOKEN = null;

  private static final ImapClientFactory GMAIL_CLIENT_FACTORY = new ImapClientFactory(
      ImapConfiguration.builder()
          .authType(AuthType.XOAUTH2)
          .hostAndPort(ImapServerDetails.GMAIL.hostAndPort())
          .noopKeepAliveIntervalSec(10)
          .useEpoll(true)
          .build()
  );

  private static GmailOAuthProfile GMAIL_PROFILE;

  public static GmailOAuthProfile getGmailProfile() {
    if (GMAIL_PROFILE == null) {
      GMAIL_PROFILE = new GmailOAuthProfile();
    }
    return GMAIL_PROFILE;
  }

  public static boolean shouldRun() {
    // Paste your App info in the static variables to add this test profile to the set to run
    return !Strings.isNullOrEmpty(APP_ID) && !Strings.isNullOrEmpty(APP_SECRET) && !Strings.isNullOrEmpty(REFRESH_TOKEN);
  }

  private final String accessToken;

  private GmailOAuthProfile() {
    super();

    GoogleCredential googleCredential = new GoogleCredential.Builder()
        .setClientSecrets(APP_ID, APP_SECRET)
        .setJsonFactory(new JacksonFactory())
        .setTransport(new NetHttpTransport())
        .build();

    googleCredential.setRefreshToken(REFRESH_TOKEN);

    try {
      googleCredential.refreshToken();
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }

    accessToken = googleCredential.getAccessToken();
  }

  @Override
  public ImapClientFactory getClientFactory() {
    return GMAIL_CLIENT_FACTORY;
  }

  @Override
  public String getPassword() {
    return accessToken;
  }

  @Override
  public String description() {
    return String.format("Gmail OAuth [%s]", USER_NAME);
  }

}