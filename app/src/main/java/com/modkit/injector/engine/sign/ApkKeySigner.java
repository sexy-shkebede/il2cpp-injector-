package com.modkit.injector.engine.sign;

import android.content.Context;

import com.android.apksig.ApkSigner;
import com.modkit.injector.engine.Logger;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

/**
 * Re-signs a patched APK. A self-signed RSA key is generated with Bouncy Castle
 * on first use and cached in the app's private storage, so rebuilds of the same
 * mod keep a stable signature (you still must uninstall the original app once,
 * because its signature differs from this one).
 */
public final class ApkKeySigner {

    private static final char[] PW = "android".toCharArray();
    private static final String ALIAS = "modkit";

    private ApkKeySigner() {}

    public static void sign(Context ctx, File unsignedApk, File outApk, Logger log) throws Exception {
        KeyStore.PrivateKeyEntry entry = loadOrCreateKey(ctx, log);
        PrivateKey key = entry.getPrivateKey();
        X509Certificate cert = (X509Certificate) entry.getCertificate();

        ApkSigner.SignerConfig signerConfig =
                new ApkSigner.SignerConfig.Builder(ALIAS, key, Collections.singletonList(cert)).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(outApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setMinSdkVersion(21)
                .build();

        signer.sign();
        if (log != null) log.ok("Signed (v1+v2+v3) -> " + outApk.getName());
    }

    private static KeyStore.PrivateKeyEntry loadOrCreateKey(Context ctx, Logger log) throws Exception {
        File ks = new File(ctx.getFilesDir(), "signer.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");

        if (ks.exists()) {
            try (FileInputStream in = new FileInputStream(ks)) {
                store.load(in, PW);
            }
            KeyStore.PrivateKeyEntry e = (KeyStore.PrivateKeyEntry)
                    store.getEntry(ALIAS, new KeyStore.PasswordProtection(PW));
            if (e != null) return e;
        }

        if (log != null) log.step("Generating signing key (first run)...");
        store.load(null, PW);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = selfSign(kp);

        store.setKeyEntry(ALIAS, kp.getPrivate(), PW, new X509Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(ks)) {
            store.store(out, PW);
        }
        return new KeyStore.PrivateKeyEntry(kp.getPrivate(), new X509Certificate[]{cert});
    }

    private static X509Certificate selfSign(KeyPair kp) throws Exception {
        BouncyCastleProvider bc = new BouncyCastleProvider();
        X500Name dn = new X500Name("CN=UnityInjector, O=Modding, C=US");
        long now = System.currentTimeMillis();
        Date from = new Date(now - 24L * 60 * 60 * 1000);
        Date to = new Date(now + 30L * 365 * 24 * 60 * 60 * 1000);
        BigInteger serial = new BigInteger(64, new SecureRandom());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bc).build(kp.getPrivate());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, serial, from, to, dn, kp.getPublic());

        return new JcaX509CertificateConverter().setProvider(bc)
                .getCertificate(builder.build(signer));
    }
}
