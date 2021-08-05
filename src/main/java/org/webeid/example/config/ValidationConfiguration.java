/*
 * Copyright (c) 2020, 2021 The Web eID Project
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.webeid.example.config;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.webeid.security.exceptions.JceException;
import org.webeid.security.nonce.NonceGenerator;
import org.webeid.security.nonce.NonceGeneratorBuilder;
import org.webeid.security.validator.AuthTokenValidator;
import org.webeid.security.validator.AuthTokenValidatorBuilder;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static javax.cache.configuration.FactoryBuilder.factoryOf;

@Configuration
public class ValidationConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ValidationConfiguration.class);

    private static final String CACHE_NAME = "nonceCache";
    private static final long NONCE_TTL_MINUTES = 5;
    private static final String CERTS_RESOURCE_PATH = "/certs/";
    public static final String TRUSTED_CERTIFICATES_JKS = "trusted_certificates.jks";

    @Value("${spring.profiles.active}")
    private String activeProfile;

    @Bean
    public CacheManager cacheManager() {
        return Caching.getCachingProvider(CaffeineCachingProvider.class.getName()).getCacheManager();
    }

    @Bean
    public Cache<String, ZonedDateTime> nonceCache() {
        final CacheManager cacheManager = cacheManager();
        Cache<String, ZonedDateTime> cache = cacheManager.getCache(CACHE_NAME);

        if (cache == null) {
            cache = createNonceCache(cacheManager);
        }
        return cache;
    }

    @Bean
    public NonceGenerator generator() {
        return new NonceGeneratorBuilder()
                .withNonceTtl(java.time.Duration.ofMinutes(NONCE_TTL_MINUTES))
                .withNonceCache(nonceCache())
                .build();
    }

    @Bean
    public X509Certificate[] loadTrustedCACertificatesFromCerFiles() {
        List<X509Certificate> caCertificates = new ArrayList<>();

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509");

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(CERTS_RESOURCE_PATH + activeProfile + "/*.cer");

            for (Resource resource : resources) {
                X509Certificate caCertificate = (X509Certificate) certFactory.generateCertificate(resource.getInputStream());
                caCertificates.add(caCertificate);
            }

        } catch (CertificateException | IOException e) {
            throw new RuntimeException("Error initializing trusted CA certificates.", e);
        }

        return caCertificates.toArray(new X509Certificate[0]);
    }

    @Bean
    public X509Certificate[] loadTrustedCACertificatesFromTrustStore() {
        List<X509Certificate> caCertificates = new ArrayList<>();

        try (InputStream is = ValidationConfiguration.class.getResourceAsStream(CERTS_RESOURCE_PATH + activeProfile + "/" + TRUSTED_CERTIFICATES_JKS)) {
            if (is == null) {
                LOG.info("Truststore file {} not found for {} profile", TRUSTED_CERTIFICATES_JKS, activeProfile);
                return new X509Certificate[0];
            }
            KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            keystore.load(is, yamlConfig().getTrustStorePassword().toCharArray());
            Enumeration<String> aliases = keystore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
                caCertificates.add(certificate);
            }
        } catch (IOException | CertificateException | KeyStoreException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Error initializing trusted CA certificates from trust store.", e);
        }

        return caCertificates.toArray(new X509Certificate[0]);
    }


    @Bean
    public AuthTokenValidator validator() {
        try {
            return new AuthTokenValidatorBuilder()
                    .withSiteOrigin(URI.create(yamlConfig().getLocalOrigin()))
                    .withNonceCache(nonceCache())
                    .withTrustedCertificateAuthorities(loadTrustedCACertificatesFromCerFiles())
                    .withTrustedCertificateAuthorities(loadTrustedCACertificatesFromTrustStore())
                    .build();
        } catch (JceException e) {
            throw new RuntimeException("Error building the Web eID auth token validator.", e);
        }
    }

    @Bean
    public YAMLConfig yamlConfig() {
        return new YAMLConfig();
    }

    private Cache<String, ZonedDateTime> createNonceCache(CacheManager cacheManager) {
        CompleteConfiguration<String, ZonedDateTime> cacheConfig = new MutableConfiguration<String, ZonedDateTime>()
                .setTypes(String.class, ZonedDateTime.class)
                .setExpiryPolicyFactory(factoryOf(new CreatedExpiryPolicy(
                        new Duration(TimeUnit.MINUTES, NONCE_TTL_MINUTES + 1))));
        return cacheManager.createCache(CACHE_NAME, cacheConfig);
    }
}
