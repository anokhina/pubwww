/*
 * Copyright 2017 Veronica Anokhina.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.org.sevn.jvert;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CookieHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpVerticle extends AbstractVerticle {
    private static final int KB = 1024;
    private static final int MB = 1024 * KB;
    private boolean useSsl = true;
    private int port = 1945;
    private JsonObject config;
    private String sslCertPathCer;// = "cert-04.cer";
    private String sslKeyPathPem;// = "key-04.pem";
    private String pubDir;

    public boolean isUseSsl() {
        return useSsl;
    }

    public int getPort() {
        return port;
    }
    
    protected Path getConfigJsonPath() {
        return new java.io.File(System.getProperty("user.home") + "/sevn-http-vert.json").toPath();
    }
    
    protected String getSslCertPathCer() {
        return sslCertPathCer;
    }
    protected String getSslKeyPathPem() {
        return sslKeyPathPem;
    }
    
    protected void configService() {
        try {
            config = new JsonObject(new String(Files.readAllBytes(getConfigJsonPath()), "UTF-8"));
            if (config.containsKey("pubDir")) {
                try {
                    pubDir = config.getString("pubDir");
                } catch (Exception e) {
                    Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, null, e);
                }
            }
            if (config.containsKey("port")) {
                try {
                    port = config.getInteger("port");
                } catch (Exception e) {
                    Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, null, e);
                }
            }
            if (config.containsKey("useSsl")) {
                try {
                    useSsl = config.getBoolean("useSsl");
                } catch (Exception e) {
                    Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, null, e);
                }
            }
            if (config.containsKey("sslCertPathCer")) {
                try {
                    sslCertPathCer = config.getString("sslCertPathCer");
                } catch (Exception e) {
                    Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, null, e);
                }
            }
            if (config.containsKey("sslKeyPathPem")) {
                try {
                    sslKeyPathPem = config.getString("sslKeyPathPem");
                } catch (Exception e) {
                    Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, null, e);
                }
            }
        } catch (java.nio.file.NoSuchFileException e1) {
            Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, e1.getClass().getName() + ": " + e1.getMessage());
        } catch (IOException ex) {
            Logger.getLogger(HttpVerticle.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    protected void setUpRouter(Router router) {
        router.route().handler(CookieHandler.create());
        router.route().handler(BodyHandler.create().setBodyLimit(50 * MB));
        SessionHandler sessionHandler = SessionHandler.create(LocalSessionStore.create(vertx));
        sessionHandler.setCookieHttpOnlyFlag(true);
        if (isUseSsl()) { //TODO
            sessionHandler.setCookieSecureFlag(true);
        }
        router.route().handler(sessionHandler);
    }
    
    
    private void redirect(HttpServerRequest r, String location) {
        r.response().setChunked(false);
        r.response().setStatusCode(302);
        r.response().putHeader("Location", location);
        r.response().end();
    }
    
    @Override
    public void start() throws Exception {
        configService();
        
        Router router = Router.router(vertx);
        setUpRouter(router);
        
        router.route("/*").handler(new LogHandler());
        
        File pubDirFile = (pubDir == null) ? (new File(new File(System.getProperty("user.home")), "pub")) : (new File(pubDir));
        router.route("/4all/*").handler(new FreeStaticHandlerImpl().setWebRoot(pubDirFile.getAbsolutePath()));
        
        router.route("/*").handler(ctx -> {
            ctx.response().putHeader("content-type", "text/html").end(simpleAnswerString(ctx));
            //ctx.fail(404);
        });
        io.vertx.core.Handler<RoutingContext> failureHandler = ctx -> {
            //TODO error template
            int statusCode = ctx.statusCode();
            HttpServerResponse response = ctx.response();
            response.putHeader("content-type", "text/plain");
            response.setChunked(false);
            response.setStatusCode(statusCode).end("Error:" + statusCode);
        };
        router.get("/*").failureHandler(failureHandler);
        router.post("/*").failureHandler(failureHandler);
        
        startServer(router);
    }
    
    protected void startServer(Router router) {
        HttpServerOptions options = new HttpServerOptions();
        if (isUseSsl()) {
            setSslCerts(options);
        }
        vertx.createHttpServer(options).requestHandler(router::accept)
                .listen(getPort());
        System.out.println("HTTP VERTICLE started");
    }
    
    protected void setSslCerts(HttpServerOptions options) {
        if (getSslKeyPathPem() != null && getSslCertPathCer() != null) {
            PemKeyCertOptions pemOptions = new PemKeyCertOptions();
            pemOptions.setKeyPath(getSslKeyPathPem()).setCertPath(getSslCertPathCer());
            options.setSsl(true);
            options.setPemKeyCertOptions(pemOptions);
        } else {
            SelfSignedCertificate certificate = SelfSignedCertificate.create(); 
            options.setKeyCertOptions(certificate.keyCertOptions());
            options.setTrustOptions(certificate.trustOptions());
            options.setSsl(true);
        }
    }
    public static void secureSet(HttpServerResponse response) {
        response
                // do not allow proxies to cache the data
                .putHeader("Cache-Control", "no-store, no-cache")
                // prevents Internet Explorer from MIME - sniffing a
                // response away from the declared content-type
                .putHeader("X-Content-Type-Options", "nosniff")
                // Strict HTTPS (for about ~6Months)
                .putHeader("Strict-Transport-Security", "max-age=" + 15768000)
                // IE8+ do not allow opening of attachments in the context of this resource
                .putHeader("X-Download-Options", "noopen")
                // enable XSS for IE
                .putHeader("X-XSS-Protection", "1; mode=block")
                // deny frames
                .putHeader("X-FRAME-OPTIONS", "DENY");
    }
    
        

//--------------------------

    private static String simpleAnswerString(RoutingContext context) {
        HttpServerRequest r = context.request();

        secureSet(context.response());

        Session session = context.session();

        Integer cnt = session.get("hitcount");
        cnt = (cnt == null ? 0 : cnt) + 1;

        session.put("hitcount", cnt);

        String resp = "<pre>"
                + r.absoluteURI() + "\n"
                + getSchemaPath(r) + "\n"
                + r.uri() + "\n"
                + r.path() + "\n"
                + r.query() + "\n"
                + cnt + "\n"
                + "</pre>";
        System.err.println("*********>" + resp);
        return resp;
    }
    
    public static String getSchemaUri(HttpServerRequest r) {
        return r.absoluteURI().substring(0, r.absoluteURI().length() - r.uri().length());
    }
    public static String getSchemaPath(HttpServerRequest r) {
        return getSchemaUri(r) + r.path();
    }
    
  public void stop() throws Exception {
        System.out.println("HTTP VERTICLE stopped");
  }
}
