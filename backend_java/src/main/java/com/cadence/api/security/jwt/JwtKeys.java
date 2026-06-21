package com.cadence.api.security.jwt;

import com.cadence.api.common.config.CadenceProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Loads the RSA keypair used to sign the bespoke RS256 JWTs minted by {@link JwtIssuer}
 * and published by {@code JwksController}. The keypair is generated once by the
 * container entrypoint (PKCS#8 private key, X.509 SubjectPublicKeyInfo public key) before
 * the JVM starts, so this bean only ever reads existing files.
 */
@Component
public class JwtKeys {

	private final RSAPrivateKey privateKey;
	private final RSAPublicKey publicKey;
	private final String kid;
	private final String issuer;
	private final String audience;

	public JwtKeys(CadenceProperties properties) {
		CadenceProperties.Jwt jwt = properties.jwt();
		this.privateKey = readPrivateKey(jwt.privateKeyPath());
		this.publicKey = readPublicKey(jwt.publicKeyPath());
		this.kid = jwt.kid();
		this.issuer = jwt.issuer();
		this.audience = jwt.audience();
	}

	public RSAPrivateKey privateKey() {
		return privateKey;
	}

	public RSAPublicKey publicKey() {
		return publicKey;
	}

	public String kid() {
		return kid;
	}

	public String issuer() {
		return issuer;
	}

	public String audience() {
		return audience;
	}

	private static RSAPrivateKey readPrivateKey(String path) {
		try {
			byte[] der = pemToDer(Files.readString(Path.of(path)));
			KeyFactory factory = KeyFactory.getInstance("RSA");
			return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
		}
		catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException("Could not load JWT private key from " + path, e);
		}
	}

	private static RSAPublicKey readPublicKey(String path) {
		try {
			byte[] der = pemToDer(Files.readString(Path.of(path)));
			KeyFactory factory = KeyFactory.getInstance("RSA");
			return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
		}
		catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException("Could not load JWT public key from " + path, e);
		}
	}

	private static byte[] pemToDer(String pem) {
		String base64 = pem.lines()
				.filter(line -> !line.startsWith("-----"))
				.reduce("", String::concat);
		return Base64.getDecoder().decode(base64);
	}
}
