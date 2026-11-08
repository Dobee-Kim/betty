package org.example.betty.domain.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Web3AuthServiceTest {
    private static final String ISSUER = "https://issuer.example.invalid";
    private static final String CLIENT_ID = "synthetic-application";
    private static RSAKey key;
    private static Web3AuthService service;

    @BeforeAll
    static void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("ephemeral-test-key").generate();
        service = new Web3AuthService(new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), ISSUER, CLIENT_ID, "RS256");
    }

    private JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT_ID).subject("synthetic-user")
                .expirationTime(new Date(System.currentTimeMillis() + 60000));
    }

    private String sign(JWTClaimsSet claims, RSAKey signingKey, JWSAlgorithm algorithm) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }

    private void rejected(JWTClaimsSet claims) throws Exception {
        String token = sign(claims, key, JWSAlgorithm.RS256);
        assertThrows(Exception.class, () -> service.verifyIdToken(token));
    }

    @Test
    void acceptsTrustedSignatureForConfiguredIssuerAndApplication() throws Exception {
        JWTClaimsSet verified = service.verifyIdToken(sign(claims().build(), key, JWSAlgorithm.RS256));
        assertEquals(ISSUER, verified.getIssuer());
        assertEquals(List.of(CLIENT_ID), verified.getAudience());
    }

    @Test
    void acceptsExplicitlyConfiguredEs256WithAnEcKey() throws Exception {
        ECKey ec = new ECKeyGenerator(Curve.P_256).keyID("ephemeral-ec-key").generate();
        Web3AuthService verifier = new Web3AuthService(new ImmutableJWKSet<>(new JWKSet(ec.toPublicJWK())), ISSUER, CLIENT_ID, "ES256");
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ec.getKeyID()).build(), claims().build());
        token.sign(new ECDSASigner(ec));
        assertEquals(CLIENT_ID, verifier.verifyIdToken(token.serialize()).getAudience().get(0));
    }

    @Test
    void rejectsOtherApplicationDespiteTrustedSignature() throws Exception {
        rejected(claims().audience("other-application").build());
    }

    @Test
    void rejectsOtherIssuerDespiteTrustedSignature() throws Exception {
        rejected(claims().issuer("https://other.example.invalid").build());
    }

    @Test
    void rejectsMissingAudience() throws Exception {
        rejected(claims().audience((String) null).build());
    }

    @Test
    void rejectsMissingIssuer() throws Exception {
        rejected(claims().issuer(null).build());
    }

    @Test
    void rejectsMultipleAudiences() throws Exception {
        rejected(claims().audience(List.of(CLIENT_ID, "other-application")).build());
    }

    @Test
    void rejectsMissingExpiry() throws Exception {
        rejected(claims().expirationTime(null).build());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        rejected(claims().expirationTime(new Date(System.currentTimeMillis() - 120000)).build());
    }

    @Test
    void rejectsFutureNotBefore() throws Exception {
        rejected(claims().notBeforeTime(new Date(System.currentTimeMillis() + 120000)).build());
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        RSAKey other = new RSAKeyGenerator(2048).generate();
        String token = sign(claims().build(), other, JWSAlgorithm.RS256);
        assertThrows(Exception.class, () -> service.verifyIdToken(token));
    }

    @Test
    void rejectsTokenSelectedAlgorithmEvenWithTrustedSignature() throws Exception {
        String token = sign(claims().build(), key, JWSAlgorithm.RS512);
        assertThrows(Exception.class, () -> service.verifyIdToken(token));
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(Exception.class, () -> service.verifyIdToken("not-an-id-token"));
    }

    @Test
    void refusesMissingOrUnsupportedTrustedConfiguration() {
        var jwks = new ImmutableJWKSet<com.nimbusds.jose.proc.SecurityContext>(new JWKSet(key.toPublicJWK()));
        for (String issuer : new String[]{null, "", " ", "http://issuer.example.invalid", "https://user@issuer.example.invalid", "https://issuer.example.invalid#fragment"}) {
            assertThrows(Exception.class, () -> new Web3AuthService(jwks, issuer, CLIENT_ID, "RS256"));
        }
        for (String client : new String[]{null, "", " ", " padded "}) {
            assertThrows(Exception.class, () -> new Web3AuthService(jwks, ISSUER, client, "RS256"));
        }
        for (String algorithm : new String[]{null, "", "HS256", "none", "RS512"}) {
            assertThrows(Exception.class, () -> new Web3AuthService(jwks, ISSUER, CLIENT_ID, algorithm));
        }
    }

    @Test
    void refusesInsecureJwksUrlBeforeAnyNetworkRequest() {
        for (String url : new String[]{null, "", "http://keys.example.invalid", "https://user@keys.example.invalid", "https://keys.example.invalid#fragment"}) {
            assertThrows(Exception.class, () -> new Web3AuthService(url, ISSUER, CLIENT_ID, "RS256"));
        }
    }
}
