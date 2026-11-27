-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.impl.**
# Android has no javax.naming (LDAP/JNDI) or sun.security internals; BC/EdDSA
# reference them in optional code paths this app never uses.
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.cert.dane.**
# Google Tink (used by security-crypto) references compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-keep class com.vpsconsole.app.data.model.** { *; }
-keep class com.vpsconsole.app.data.db.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.spongycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.spongycastle.** { *; }
