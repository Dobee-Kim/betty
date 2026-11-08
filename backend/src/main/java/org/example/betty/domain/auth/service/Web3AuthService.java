package org.example.betty.domain.auth.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class Web3AuthService {
    private final JWKSource<SecurityContext> jwkSource;
    private final String issuer;
    private final String clientId;
    private final JWSAlgorithm algorithm;

    @Autowired
    public Web3AuthService(
            @Value("${web3auth.jwks-url}") String jwksUrl,
            @Value("${web3auth.expected-issuer}") String issuer,
            @Value("${web3auth.client-id}") String clientId,
            @Value("${web3auth.jws-algorithm}") String algorithm) throws Exception {
        this(new RemoteJWKSet<>(httpsUrl(jwksUrl)), issuer, clientId, algorithm);
    }

    Web3AuthService(JWKSource<SecurityContext> jwkSource, String issuer, String clientId, String algorithm) throws Exception {
        httpsUrl(issuer);
        if (clientId == null || clientId.isBlank() || !clientId.equals(clientId.trim())) {
            throw new IllegalArgumentException("Web3Auth client ID must be explicitly configured");
        }
        JWSAlgorithm configured = algorithm == null ? null : JWSAlgorithm.parse(algorithm);
        if (configured == null || !Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256).contains(configured)) {
            throw new IllegalArgumentException("Web3Auth JWS algorithm must be RS256 or ES256");
        }
        this.jwkSource = jwkSource;
        this.issuer = issuer;
        this.clientId = clientId;
        this.algorithm = configured;
    }

    private static URL httpsUrl(String value) throws Exception {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Web3Auth HTTPS URL must be explicitly configured");
        }
        URI uri = new URI(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Web3Auth URLs must use HTTPS without credentials or fragments");
        }
        return uri.toURL();
    }

    public JWTClaimsSet verifyIdToken(String idToken) throws Exception {
        SignedJWT signedJWT = SignedJWT.parse(idToken);
        if (!algorithm.equals(signedJWT.getHeader().getAlgorithm())) {
            throw new BadJWTException("Unexpected ID token algorithm");
        }
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(algorithm, jwkSource));
        JWTClaimsSet claims = processor.process(signedJWT, null);
        if (!issuer.equals(claims.getIssuer()) || !List.of(clientId).equals(claims.getAudience())) {
            throw new BadJWTException("ID token is not intended for this application");
        }
        if (claims.getExpirationTime() == null || !claims.getExpirationTime().after(new Date())) {
            throw new BadJWTException("ID token must have a future expiration");
        }
        return claims;
    }
}
