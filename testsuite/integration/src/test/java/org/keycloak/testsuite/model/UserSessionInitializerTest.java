package org.keycloak.testsuite.model;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.UserSessionProviderFactory;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.managers.UserManager;
import org.keycloak.services.managers.UserSessionManager;
import org.keycloak.testsuite.rule.KeycloakRule;
import org.keycloak.util.Time;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class UserSessionInitializerTest {

    @ClassRule
    public static KeycloakRule kc = new KeycloakRule();

    private KeycloakSession session;
    private RealmModel realm;
    private UserSessionManager sessionManager;

    @Before
    public void before() {
        session = kc.startSession();
        realm = session.realms().getRealm("test");
        session.users().addUser(realm, "user1").setEmail("user1@localhost");
        session.users().addUser(realm, "user2").setEmail("user2@localhost");
        sessionManager = new UserSessionManager(session);
    }

    @After
    public void after() {
        resetSession();
        session.sessions().removeUserSessions(realm);
        UserModel user1 = session.users().getUserByUsername("user1", realm);
        UserModel user2 = session.users().getUserByUsername("user2", realm);

        UserManager um = new UserManager(session);
        um.removeUser(realm, user1);
        um.removeUser(realm, user2);
        kc.stopSession(session, true);
    }

    @Test
    public void testUserSessionInitializer() {
        UserSessionModel[] origSessions = createSessions();

        resetSession();

        // Create and persist offline sessions
        for (UserSessionModel origSession : origSessions) {
            UserSessionModel userSession = session.sessions().getUserSession(realm, origSession.getId());
            for (ClientSessionModel clientSession : userSession.getClientSessions()) {
                sessionManager.createOrUpdateOfflineSession(clientSession, userSession);
            }
        }

        resetSession();

        // Delete cache (persisted sessions are still kept)
        session.sessions().onRealmRemoved(realm);

        // Clear ispn cache to ensure initializerState is removed as well
        InfinispanConnectionProvider infinispan = session.getProvider(InfinispanConnectionProvider.class);
        infinispan.getCache(InfinispanConnectionProvider.OFFLINE_SESSION_CACHE_NAME).clear();

        resetSession();

        ClientModel testApp = realm.getClientByClientId("test-app");
        ClientModel thirdparty = realm.getClientByClientId("third-party");
        Assert.assertEquals(0, session.sessions().getOfflineSessionsCount(realm, testApp));
        Assert.assertEquals(0, session.sessions().getOfflineSessionsCount(realm, thirdparty));

        int started = Time.currentTime();

        try {
            // Set some offset to ensure lastSessionRefresh will be updated
            Time.setOffset(10);

            // Load sessions from persister into infinispan/memory
            UserSessionProviderFactory userSessionFactory = (UserSessionProviderFactory) session.getKeycloakSessionFactory().getProviderFactory(UserSessionProvider.class);
            userSessionFactory.loadPersistentSessions(session.getKeycloakSessionFactory(), 10, 2);

            resetSession();

            // Assert sessions are in
            testApp = realm.getClientByClientId("test-app");
            Assert.assertEquals(3, session.sessions().getOfflineSessionsCount(realm, testApp));
            Assert.assertEquals(1, session.sessions().getOfflineSessionsCount(realm, thirdparty));

            List<UserSessionModel> loadedSessions = session.sessions().getOfflineUserSessions(realm, testApp, 0, 10);
            UserSessionProviderTest.assertSessions(loadedSessions, origSessions);

            UserSessionPersisterProviderTest.assertSessionLoaded(loadedSessions, origSessions[0].getId(), session.users().getUserByUsername("user1", realm), "127.0.0.1", started, started+10, "test-app", "third-party");
            UserSessionPersisterProviderTest.assertSessionLoaded(loadedSessions, origSessions[1].getId(), session.users().getUserByUsername("user1", realm), "127.0.0.2", started, started+10, "test-app");
            UserSessionPersisterProviderTest.assertSessionLoaded(loadedSessions, origSessions[2].getId(), session.users().getUserByUsername("user2", realm), "127.0.0.3", started, started+10, "test-app");
        } finally {
            Time.setOffset(0);
        }
    }

    private ClientSessionModel createClientSession(ClientModel client, UserSessionModel userSession, String redirect, String state, Set<String> roles, Set<String> protocolMappers) {
        ClientSessionModel clientSession = session.sessions().createClientSession(realm, client);
        if (userSession != null) clientSession.setUserSession(userSession);
        clientSession.setRedirectUri(redirect);
        if (state != null) clientSession.setNote(OIDCLoginProtocol.STATE_PARAM, state);
        if (roles != null) clientSession.setRoles(roles);
        if (protocolMappers != null) clientSession.setProtocolMappers(protocolMappers);
        return clientSession;
    }

    private UserSessionModel[] createSessions() {
        UserSessionModel[] sessions = new UserSessionModel[3];
        sessions[0] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.1", "form", true, null, null);

        Set<String> roles = new HashSet<String>();
        roles.add("one");
        roles.add("two");

        Set<String> protocolMappers = new HashSet<String>();
        protocolMappers.add("mapper-one");
        protocolMappers.add("mapper-two");

        createClientSession(realm.getClientByClientId("test-app"), sessions[0], "http://redirect", "state", roles, protocolMappers);
        createClientSession(realm.getClientByClientId("third-party"), sessions[0], "http://redirect", "state", new HashSet<String>(), new HashSet<String>());

        sessions[1] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user1", realm), "user1", "127.0.0.2", "form", true, null, null);
        createClientSession(realm.getClientByClientId("test-app"), sessions[1], "http://redirect", "state", new HashSet<String>(), new HashSet<String>());

        sessions[2] = session.sessions().createUserSession(realm, session.users().getUserByUsername("user2", realm), "user2", "127.0.0.3", "form", true, null, null);
        createClientSession(realm.getClientByClientId("test-app"), sessions[2], "http://redirect", "state", new HashSet<String>(), new HashSet<String>());

        resetSession();

        return sessions;
    }

    private void resetSession() {
        kc.stopSession(session, true);
        session = kc.startSession();
        realm = session.realms().getRealm("test");
        sessionManager = new UserSessionManager(session);
    }

}
