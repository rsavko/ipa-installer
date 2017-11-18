package com.reactivebytes.manifestgenerator;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.model.lifecycle.LifecycleFilter;
import com.amazonaws.services.s3.model.lifecycle.LifecycleTagPredicate;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;
import nl.pvanassen.bplist.converter.ConvertToXml;
import org.slf4j.Logger;
import xmlwise.Plist;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.UUID.randomUUID;
import static org.slf4j.LoggerFactory.getLogger;

public class MainVerticle extends AbstractVerticle {
    private static final Logger log = getLogger(MainVerticle.class);
    private static final String linkTmpl = "<a href=\"itms-services://?action=download-manifest&url=%s\">%s</a>";
    private static final int DEFAULT_EXPIRATION_TIMEOUT = 30; // mins

    private AmazonS3 s3;
    private Pattern pattern = Pattern.compile("Payload/[^/]*.app/Info.plist");

    @Override
    public void start(Future<Void> fut) {
        Router router = Router.router(vertx);
        router.route("/").handler(routingContext -> {
            routingContext.reroute("/assets/index.html");
        });

        router.route("/assets/*").handler(StaticHandler.create("assets"));
        router.route().handler(BodyHandler.create().setUploadsDirectory("uploads").setDeleteUploadedFilesOnEnd(true));
        router.post("/file").blockingHandler(this::handleFileUpload, false);
        router.post("/link").blockingHandler(this::handleFormUpload, false);

        log.info("Starting server on port {}...", config().getInteger("http.port"));

        vertx.createHttpServer()
                .requestHandler(router::accept)
                .listen(config().getInteger("http.port", 80),
                        http -> {
                            if (http.succeeded()) {
                                log.info("Server started.");
                                s3 = AmazonS3ClientBuilder.standard().withCredentials(getCredentials()).withRegion(Regions.DEFAULT_REGION).build();
                                deleteAllBuckets();
                                fut.complete();
                            } else {
                                log.info("Failed to start server.");
                                fut.fail(http.cause());
                            }
                        }
                );
    }

    private AWSStaticCredentialsProvider getCredentials() {
        AWSCredentials credentials = new BasicAWSCredentials(config().getString("aws.key"), config().getString("aws.secret"));
        return new AWSStaticCredentialsProvider(credentials);
    }

    private void handleFormUpload(RoutingContext ctx) {
        final HttpServerResponse response = ctx.response();
        final String url = ctx.request().getFormAttribute("link");
        Path path = null;
        try {
            path = Files.createTempFile(randomUUID().toString(), ".ipa");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            processFile(path.toString(), response);
        } catch (Exception e) {
            log.error("Invalid URL: {}", url);
            sendError(response);
            if (path != null && path.toFile().exists()) {
                if (!path.toFile().delete()) {
                    path.toFile().deleteOnExit();
                }
            }
        }
    }

    private void handleFileUpload(RoutingContext ctx) {
        ctx.fileUploads().forEach(f -> {
            log.info("Processing file '{}'", f.fileName());
            processFile(f.uploadedFileName(), ctx.response());
        });
    }

    private void processFile(String filePath, HttpServerResponse response) {
        String bucketName = generateBucketName();
        createBucket(bucketName);

        try (ZipFile zip = new ZipFile(filePath)) {
            final Optional<? extends ZipEntry> infoEntry = zip.stream().filter(z -> pattern.matcher(z.getName()).matches()).findFirst();

            infoEntry.ifPresent(e -> {
                Path path = null;
                try {
                    path = Files.createTempFile(randomUUID().toString(), ".xml");
                    final InputStream is = zip.getInputStream(e);

                    Files.copy(is, path, StandardCopyOption.REPLACE_EXISTING);
                    Map<String, Object> plist;
                    try {
                        String convertedXml = new ConvertToXml().convertToXml(path.toFile()).toString();
                        plist = Plist.fromXml(convertedXml);
                    } catch (Exception exc) {
                        plist = Plist.load(path.toFile());
                    }

                    String displayName = (String) plist.getOrDefault("CFBundleDisplayName", plist.get("CFBundleName"));
                    log.info("Display name is '{}'", displayName);

                    String bundleId = (String) plist.get("CFBundleIdentifier");
                    log.info("Bundle ID is '{}'", bundleId);
                    String version = (String) plist.get("CFBundleShortVersionString");
                    log.info("Version is '{}'", version);

                    String appName = "application.ipa";//f.fileName().replaceAll("[^\\p{L}\\p{Nd}]+", "");
                    String manifestName = "application.xml";
                    String appUrl = String.format("https://%s.s3.amazonaws.com/%s", bucketName, appName);
                    String manifestUrl = String.format("https://%s.s3.amazonaws.com/%s", bucketName, manifestName);

                    log.info("Uploading file into S3 bucket '{}'...", bucketName);
                    s3.putObject(new PutObjectRequest(bucketName, appName, Paths.get(filePath).toFile()));
                    log.info("Making S3 bucket '{}' public...", bucketName);
                    setObjectPublic(appName, bucketName, s3);

                    log.info("Generating template file...");
                    final Buffer plistTemplate = vertx.fileSystem().readFileBlocking("tmpl.plist");
                    String generatedPlist = plistTemplate.toString().replace("{name}", displayName).replace("{version}", version).replace("{id}", bundleId).replace("{url}", appUrl);

                    log.info("Uploading template file...");
                    s3.putObject(bucketName, manifestName, generatedPlist);
                    setObjectPublic(manifestName, bucketName, s3);

                    String link = String.format(linkTmpl, manifestUrl, displayName);
                    log.info("Making bucket '{}' read-only...", bucketName);
                    setBucketReadOnly(bucketName, s3);
                    log.info("Generated link is: {}", link);

                    Buffer buf = vertx.fileSystem().readFileBlocking("assets/result.html");
                    String tmpl = buf.toString();
                    tmpl = tmpl.replace("{url}", link).replace("{expiration}", String.valueOf(config().getInteger("object.expiration.delay", DEFAULT_EXPIRATION_TIMEOUT)));
                    response.end(tmpl);
                    scheduleDelete(bucketName);
                } catch (Exception e1) {
                    log.error("", e);
                    sendError(response);
                } finally {
                    if (path != null) {
                        if (!path.toFile().delete()) {
                            path.toFile().deleteOnExit();
                        }
                    }
                }
            });

            infoEntry.orElseGet(() -> {
                sendError(response);
                return null;
            });

        } catch (Exception e) {
            response.setStatusCode(500);
            response.setStatusMessage(e.getMessage());
            deleteBucket(bucketName);
            sendError(response);
        }
    }

    private void scheduleDelete(String bucketName) {
        vertx.setTimer(TimeUnit.valueOf(config().getString("object.expiration.timeunit"))
                        .toMillis(config().getInteger("object.expiration.delay", DEFAULT_EXPIRATION_TIMEOUT)),
                id -> deleteBucket(bucketName));
    }

    private void sendError(HttpServerResponse response) {
        response.sendFile(getErrorFile());
    }

    private String getErrorFile() {
        return "assets/error.html";
    }

    private String generateBucketName() {
        return "apps-" + randomUUID();
    }

    private void deleteBucket(String bucketName) {
        log.info("Preparing to deleting bucket '{}'...", bucketName);
        s3.listObjects(bucketName).getObjectSummaries().forEach(obj -> {
            log.info("\tdeleting '{}' (size: {})", obj.getKey(), obj.getSize());
            s3.deleteObject(obj.getBucketName(), obj.getKey());
        });
        log.info("Deleting bucket '{}'...", bucketName);
        s3.deleteBucket(bucketName);
        log.info("Bucket '{}' deleted.", bucketName);
    }

    private void deleteAllBuckets() {
        s3.listBuckets().parallelStream().forEach(bucket -> deleteBucket(bucket.getName()));
    }

    private Bucket createBucket(String bucketName) {
        Bucket bucket = s3.createBucket(new CreateBucketRequest(bucketName).withCannedAcl(CannedAccessControlList.PublicReadWrite));

        BucketLifecycleConfiguration.Rule rule =
                new BucketLifecycleConfiguration.Rule()
                        .withId("Delete rule")
                        .withFilter(new LifecycleFilter(
                                new LifecycleTagPredicate(new Tag("delete", "true"))))
                        .withExpirationInDays(1)
                        .withStatus(BucketLifecycleConfiguration.ENABLED);

        s3.setBucketLifecycleConfiguration(bucket.getName(), new BucketLifecycleConfiguration().withRules(rule));
        return bucket;

    }

    private void setBucketReadOnly(String bucketName, AmazonS3 s3) {
        s3.setBucketAcl(bucketName, CannedAccessControlList.PublicRead);
    }

    private void setObjectPublic(String objName, String bucketName, AmazonS3 s3) {
        s3.setObjectAcl(bucketName, objName, CannedAccessControlList.PublicRead);
    }

}
