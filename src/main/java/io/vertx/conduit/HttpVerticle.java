package io.vertx.conduit;

import io.vertx.conduit.errors.AuthenticationError;
import io.vertx.conduit.errors.ConduitError;
import io.vertx.conduit.errors.ErrorMessages;
import io.vertx.conduit.errors.RegistrationError;
import io.vertx.conduit.users.models.ConduitModelType;
import io.vertx.conduit.users.models.User;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.groovy.ext.auth.jwt.JWTAuth_GroovyExtension;

import static io.vertx.conduit.MessagingProps.*;
import static io.vertx.conduit.UserDAV.*;
import static io.vertx.conduit.users.ArticleDAV.MESSAGE_ARTICLES;

public class HttpVerticle extends AbstractVerticle {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpVerticle.class);

    // Authentication provider for the api
    private JWTAuth jwtAuth;


    @Override
    public void start(Future<Void> startFuture) {

        LOGGER.info("HttpVerticle starting with config for " + config().getString("env"));

        // Configure authentication with JWT
        jwtAuth = JWTAuth.create(vertx, new JsonObject().put("keyStore", new JsonObject()
                .put("type", "jceks")
                .put("path", "keystore.jceks")
                .put("password", "secret")));

        // create a apiRouter to handle the API
        Router baseRouter = Router.router(vertx);
        Router apiRouter = Router.router(vertx);

        baseRouter.route("/").handler(routingContext -> {
            HttpServerResponse response = routingContext.response();
            response.putHeader("content-type", "text/plain").end("Hello Vert.x!");
        });

        apiRouter.route("/user*").handler(BodyHandler.create());
//    apiRouter.route("/*").handler(JWTAuthHandler.create(jwtAuth));
        apiRouter.get("/user").handler(JWTAuthHandler.create(jwtAuth)).handler(this::getCurrentUser);
        apiRouter.put("/user").handler(JWTAuthHandler.create(jwtAuth)).handler(this::updateUser);
        apiRouter.post("/users").handler(this::registerUser);
        apiRouter.post("/users/login").handler(this::loginUser);
        apiRouter.get("/profiles/:username").handler(this::getProfile);
        apiRouter.post("/profiles/:username/follow").handler(JWTAuthHandler.create(jwtAuth)).handler(this::followUser);
        apiRouter.delete("/profiles/:username/follow").handler(JWTAuthHandler.create(jwtAuth)).handler(this::unFollowUser);
        // articles
        apiRouter.route("/article*").handler(BodyHandler.create());
        apiRouter.get("/articles").handler(this::getArticles);
        apiRouter.post("/articles").handler(this::createArticle);
        apiRouter.get("/articles/:slug").handler(this::lookupArticle);
        apiRouter.put("/articles/:slug").handler(JWTAuthHandler.create(jwtAuth)).handler(this::updateArticle);
        apiRouter.delete("/articles/:slug").handler(JWTAuthHandler.create(jwtAuth)).handler(this::deleteArticle);
        apiRouter.put("/articles/:slug").handler(this::updateArticle);

        baseRouter.mountSubRouter("/api", apiRouter);

//    new HttpServerOptions()
//      .setSsl(true)
//      .setKeyStoreOptions(new JksOptions().setPath("server-keystore.jks").setPassword("secret")))
        vertx.createHttpServer()
                .requestHandler(baseRouter::accept)
                .listen(8080, result -> {
                    if (result.succeeded()) {
                        startFuture.complete();
                    } else {
                        startFuture.fail(result.cause());
                    }
                });

    }

    private void getArticles(RoutingContext routingContext) {
    }

    private void lookupArticle(RoutingContext routingContext) {

        String slug = routingContext.request().getParam("slug");
        if (slug == null || slug.isEmpty()) {
            routingContext.response().setStatusCode(400).end();
        }

        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, LOOKUP_BY_FIELD)
                .put(KEY_FIELD, "slug")
                .put(KEY_VALUE, slug);

        vertx.eventBus().send(MESSAGE_ARTICLES, message, ar -> {

            if (ar.succeeded()) {
                JsonObject returned = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                LOGGER.info("Returned: " + returned);

                JsonObject returnedJson = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                final Article returnedArticle = new Article(returnedJson);
                routingContext.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(returnedArticle.toConduitJson()));
            } else {
                LOGGER.info("Save unsuccessful. Returning: " + ar.cause().getMessage());
                routingContext.response().setStatusCode(422)
                        .putHeader("content-type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(new ConduitError(ar.cause().getMessage())));
            }
        });
    }

    private Future<User> lookupUserFromJWT(String token) {
        Future<User> retVal = Future.future();

        extractUserFromJWTToken(token).setHandler(ar -> {
            if (ar.succeeded()) {
                // get the logged in user
                JsonObject jwtUser = ar.result();
                // get the logged in user from the database
                getUserFromEmail(jwtUser.getString("email")).setHandler(ar2 -> {
                    if (ar2.succeeded()) {
                        final User user = new User(ar2.result());
                        retVal.complete(user);
                    }else {
                        retVal.fail(ar2.cause());
                    }
                });
            }else{
                retVal.fail(ar.cause());
            }
        });
        return retVal;
    }

    private void createArticle(RoutingContext routingContext) {
        JsonObject b = routingContext.getBodyAsJson().getJsonObject("article");
        Article articleToSave = new Article(b);

        // get the Author/User from the JWT token
        String headerAuth = routingContext.request().getHeader("Authorization");
        String[] values = headerAuth.split(" ");

        lookupUserFromJWT(values[1]).setHandler(ar ->{
            if (ar.succeeded()) {
                User user = ar.result();
                articleToSave.setAuthor(user);
                save(articleToSave, ConduitModelType.ARTICLE).setHandler(ar2 -> {
                    if (ar2.succeeded()) {
                        LOGGER.info("Save successful. Returning: " + ar2.result().toString());
                        final Article returnedArticle = new Article(ar2.result());
                        routingContext.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json; charset=utf-8")
                                .end(Json.encodePrettily(returnedArticle.toConduitJson()));
                    } else {
                        LOGGER.info("Save unsuccessful. Returning: " + ar2.cause().getMessage());
                        routingContext.response().setStatusCode(422)
                                .putHeader("content-type", "application/json; charset=utf-8")
                                .end(Json.encodePrettily(new ConduitError(ar.cause().getMessage())));
                    }
                });
            }else{
                LOGGER.info("User lookup unsuccessful. Returning: " + ar.cause().getMessage());
                routingContext.response().setStatusCode(422)
                        .putHeader("content-type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(new ConduitError(ar.cause().getMessage())));
            }
        });
    }

    private Future<Article> updateArticle(Article articleToUpdate) {
        Future<Article> retVal = Future.future();

        JsonObject update = new JsonObject()
                .put("title", articleToUpdate.getTitle())
                .put("description", articleToUpdate.getDescription())
                .put("body", articleToUpdate.getBody());

        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, MESSAGE_ACTION_UPDATE)
                .put(KEY_FIELD, "slug")
                .put(KEY_VALUE, articleToUpdate.getSlug())
                .put(DOCUMENT, update);

        vertx.eventBus().send(MESSAGE_ARTICLES, message, ar -> {

            if (ar.succeeded()) {
                JsonObject returned = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                LOGGER.info("Returned: " + returned);
                final Article returnedArticle = new Article(returned);
                retVal.complete(returnedArticle);
            } else {
                retVal.fail(ar.cause());
            }
        });
        return retVal;
    }

    private Future<JsonObject> save(ConduitDomainModel objectToSave, ConduitModelType modelType) {

        Future<JsonObject> retVal = Future.future();

        JsonObject message = new JsonObject()
                .put(MESSAGE_CREATE_OBJECT, objectToSave.toMongoJson())
                .put(MESSAGE_OBJECT_TYPE, modelType.name);
        if (modelType.equals(ConduitModelType.ARTICLE)) {
            message.put(MESSAGE_ACTION, MESSAGE_ACTION_CREATE_ARTICLE);
        }

        vertx.eventBus().send(MESSAGE_ADDRESS, message, ar -> {

            if (ar.succeeded()) {
                JsonObject returned = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                LOGGER.info("Returned: " + returned);
                retVal.complete(returned);
            } else {
                retVal.fail(ar.cause());
            }
        });
        return retVal;
    }

    private void deleteArticle(RoutingContext routingContext) {
        final String slug = routingContext.request().getParam("slug");

        LOGGER.info(slug);

        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, DELETE)
                .put(MESSAGE_LOOKUP_FIELD, "slug")
                .put(MESSAGE_LOOKUP_VALUE, slug);

        vertx.eventBus().send(MESSAGE_ARTICLES, message, ar ->{
            if (ar.succeeded()) {
                routingContext.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json; charset=utf-8")
                        //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                        .end();
            } else{
                routingContext.response().setStatusCode(422)
                        .putHeader("content-type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(new ConduitError(slug)));
            }
        });


    }

    private void updateArticle(RoutingContext routingContext) {
        final String slug = routingContext.request().getParam("slug");

        // get the new values
        final JsonObject newArticleValuesJson = routingContext.getBodyAsJson().getJsonObject("article");
        Article articleToSave = new Article(newArticleValuesJson);
        articleToSave.setSlug(slug);

        // get the Author/User from the JWT token
        String[] values = routingContext.request().getHeader("Authorization").split(" ");

        lookupUserFromJWT(values[1]).setHandler(ar ->{
            if (ar.succeeded()) {
                User user = ar.result();
                articleToSave.setAuthor(user);
                updateArticle(articleToSave).setHandler(ar2 ->{
                    if (ar2.succeeded()) {
                        LOGGER.info("Save successful. Returning: " + ar2.result().toString());
                        routingContext.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json; charset=utf-8")
                                .end(Json.encodePrettily(ar2.result().toConduitJson()));

                    }else{
                        routingContext.response().setStatusCode(422)
                                .putHeader("content-type", "application/json; charset=utf-8")
                                .end(Json.encodePrettily(new ConduitError(newArticleValuesJson.getString("title"))));
                    }
                });
            }else{
                routingContext.response().setStatusCode(422)
                        .putHeader("content-type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(new ConduitError(newArticleValuesJson.getString("title"))));
            }
        });



    }

    // Article methods


    // Users methods

    private void unFollowUser(RoutingContext routingContext) {
        String username = routingContext.request().getParam("username");
        if (username == null || username.isEmpty()) {
            routingContext.response().setStatusCode(400).end();
        }

        String headerAuth = routingContext.request().getHeader("Authorization");
        String[] values = headerAuth.split(" ");

        extractUserFromJWTToken(values[1]).setHandler(ar -> {
            if (ar.succeeded()) {
                JsonObject jwtUser = ar.result();

                JsonObject message = new JsonObject()
                        .put(MESSAGE_ACTION, MESSAGE_ACTION_UNFOLLOW)
                        .put(MESSAGE_FOLLOW_USER_FOLLOWED_USER, username)
                        .put(MESSAGE_FOLLOW_USER_FOLLOWER, jwtUser.getString("email"));
                vertx.eventBus().send(MESSAGE_ADDRESS, message, ar2 -> {
                    if (ar2.succeeded()) {
                        JsonObject j = ((JsonObject) ar2.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                        User returnedUser = new User(j);

                        routingContext.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json; charset=utf-8")
                                //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                                .end(Json.encodePrettily(returnedUser.toProfileJson()));
                    } else {
                        routingContext.response().setStatusCode(422)
                                .putHeader("content-type", "application/json; charset=utf-8")
                                //.putHeader("Content-Length", String.valueOf(loginError.toString().length()))
                                .end(Json.encodePrettily(ar2.cause()));
                    }
                });
            } else {
                throw new RuntimeException(ar.cause().getMessage());
            }
        });
    }

    private void updateUser(RoutingContext routingContext) {

        // get the new values
        final JsonObject newUserValuesJson = routingContext.getBodyAsJson().getJsonObject("user");

        // get the JWT token to find the calling user
        String headerAuth = routingContext.request().getHeader("Authorization");
        System.out.println("headerAuth: " + headerAuth);

        String[] values = headerAuth.split(" ");
        System.out.println("values[1]: " + values[1]);

        extractUserFromJWTToken(values[1]).setHandler(ar -> {
            if (ar.succeeded()) {

                // get the logged in user
                JsonObject jwtUser = ar.result();

                // get the logged in user from the database
                getUserFromEmail(jwtUser.getString("email")).setHandler(ar2 -> {
                    if (ar2.succeeded()) {
                        JsonObject existingUser = ar2.result();

                        // compare userToUpdate and emailUser to verify they are the same person
                        if (!jwtUser.getString("email").equalsIgnoreCase(existingUser.getString("email"))) {
                            throw new RuntimeException("Can't update other users");
                        }

                        JsonObject update = getFieldsToUpdate(newUserValuesJson, existingUser);

                        // only perform the update if necessary
                        if (update.fieldNames().size() >= 1) {

                            updateUserByUsername(existingUser.getString("username"), update).setHandler(ar3 -> {
                                if (ar3.succeeded()) {
                                    User retunedUser = new User(ar3.result());
                                    // get the JWT Token
//                  returnedUser.setToken(jwtAuth.generateToken(principal, new JWTOptions().setIgnoreExpiration(true)));

                                    routingContext.response()
                                            .setStatusCode(200)
                                            .putHeader("Content-Type", "application/json; charset=utf-8")
                                            //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                                            .end(Json.encodePrettily(retunedUser.toConduitJson()));
                                } else {
                                    System.out.println("Did Not Find User");
                                    routingContext.response().setStatusCode(422)
                                            .putHeader("content-type", "application/json; charset=utf-8")
                                            //.putHeader("Content-Length", String.valueOf(loginError.toString().length()))
                                            .end(Json.encodePrettily(new AuthenticationError(ErrorMessages.AUTHENTICATION_ERROR_DEFAULT + " " + ar.cause().getMessage())));
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private JsonObject getFieldsToUpdate(JsonObject newUserValuesJson, JsonObject existingUser) {
        // create a JsonObject to store the new parameters
        JsonObject update = new JsonObject();

        // compare the existing values with the new values
        if (isNewValue(newUserValuesJson.getString("email"), existingUser.getString("email"))) {
            update.put("email", newUserValuesJson.getString("email"));
        }
        // compare the existing values with the new values
        if (isNewValue(newUserValuesJson.getString("bio"), existingUser.getString("bio"))) {
            update.put("bio", newUserValuesJson.getString("bio"));
        }
        // compare the existing values with the new values
        if (isNewValue(newUserValuesJson.getString("image"), existingUser.getString("image"))) {
            update.put("image", newUserValuesJson.getString("image"));
        }
        return update;

    }

    private boolean isNewValue(String a, String b) {
        if (a == null) return false;
        if (a.isEmpty()) return false;
        if (a.equalsIgnoreCase(b)) return false;
        if (!a.equalsIgnoreCase(b)) return true;
        return false;
    }

    private void updateUserWithFutures(RoutingContext routingContext, Handler<AsyncResult> asyncResultHandler) {

        final User userToUpdate = Json.decodeValue(routingContext.getBodyAsJson().getJsonObject("user").toString(), User.class);

        String headerAuth = routingContext.request().getHeader("Authorization");
        System.out.println("headerAuth: " + headerAuth);

        String[] values = headerAuth.split(" ");
        System.out.println("values[1]: " + values[1]);

        Future future = Future.future();
        future.setHandler(asyncResultHandler);

        Future jwtFuture = Future.future();

    }

    private void followUser(RoutingContext routingContext) {

        String username = routingContext.request().getParam("username");
        if (username == null || username.isEmpty()) {
            routingContext.response().setStatusCode(400).end();
        }

        String headerAuth = routingContext.request().getHeader("Authorization");
        System.out.println("headerAuth: " + headerAuth);

        String[] values = headerAuth.split(" ");
        System.out.println("values[1]: " + values[1]);

        extractUserFromJWTToken(values[1]).setHandler(ar -> {
            System.out.println(ar.result());

            JsonObject message = new JsonObject()
                    .put(MESSAGE_ACTION, MESSAGE_ACTION_FOLLOW_USER)
                    .put(MESSAGE_FOLLOW_USER_FOLLOWED_USER, username)
                    .put(MESSAGE_FOLLOW_USER_FOLLOWER, ar.result().getString("email"));

            vertx.eventBus().send(MESSAGE_ADDRESS, message, r -> {

                if (r.succeeded()) {

                    JsonObject details = ((JsonObject) r.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                    User follower = new User(details.getJsonObject(MESSAGE_FOLLOW_USER_FOLLOWER));
                    User followed = new User(details.getJsonObject(MESSAGE_FOLLOW_USER_FOLLOWED_USER));

                    routingContext.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json; charset=utf-8")
                            //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                            .end(Json.encodePrettily(followed.toProfileJson()));

                } else {

                    routingContext.response().setStatusCode(422)
                            .putHeader("content-type", "application/json; charset=utf-8")
                            //.putHeader("Content-Length", String.valueOf(loginError.toString().length()))
                            .end(Json.encodePrettily(r.cause()));
                }
            });

        });

    }

    private Future<JsonObject> updateUserByUsername(String username, JsonObject userValues) {
        Future<JsonObject> retVal = Future.future();

        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, MESSAGE_ACTION_UPDATE)
                .put(MESSAGE_UPDATE_EXISTING, username)
                .put(MESSAGE_UPDATE_NEW, userValues);

        vertx.eventBus().send(MESSAGE_ADDRESS, message, ar -> {

            if (ar.succeeded()) {
                retVal.complete(((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS));
            } else {
                retVal.fail(ar.cause());
            }
        });

        return retVal;
    }

    private Future<JsonObject> extractUserFromJWTToken(String token) {
        Future<JsonObject> retVal = Future.future();
        jwtAuth.authenticate(new JsonObject()
                .put("jwt", token), res -> {
            if (res.succeeded()) {
                io.vertx.ext.auth.User theUser = res.result();
                retVal.complete(theUser.principal());
            } else {
                retVal.fail(res.cause());
            }
        });
        return retVal;
    }

    private Future<JsonObject> getUserFromEmail(String email) {
        Future<JsonObject> retVal = Future.future();

        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, MESSAGE_ACTION_LOOKUP_USER_BY_EMAIL)
                .put(MESSAGE_LOOKUP_CRITERIA, email);

        vertx.eventBus().send(MESSAGE_ADDRESS, message, ar -> {

            if (ar.succeeded()) {
                JsonObject userJson = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                retVal.complete(userJson);
            } else {
                retVal.fail(ar.cause());
            }
        });

        return retVal;
    }


    private void getProfile(RoutingContext routingContext) {
        String username = routingContext.request().getParam("username");
        if (username == null || username.isEmpty()) {
            routingContext.response().setStatusCode(400).end();
        } else {
            JsonObject message = new JsonObject()
                    .put(MESSAGE_ACTION, MESSAGE_ACTION_LOOKUP_USER_BY_USERNAME)
                    .put(MESSAGE_LOOKUP_CRITERIA, username);

            vertx.eventBus().send(MESSAGE_ADDRESS, message, ar -> {

                if (ar.succeeded()) {
                    JsonObject userJson = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                    final User returnedUser = new User(userJson);
                    routingContext.response()
                            .setStatusCode(200)
                            .putHeader("Content-Type", "application/json; charset=utf-8")
                            //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                            .end(Json.encodePrettily(returnedUser.toProfileJson()));
                } else {
                    System.out.println("Did Not Find User");
                    routingContext.response().setStatusCode(422)
                            .putHeader("content-type", "application/json; charset=utf-8")
                            //.putHeader("Content-Length", String.valueOf(loginError.toString().length()))
                            .end(Json.encodePrettily(new AuthenticationError(ErrorMessages.AUTHENTICATION_ERROR_DEFAULT + " " + ar.cause().getMessage())));
                }
            });
        }
    }

    private Future<JsonObject> getPrincipalFromToken(String token) {

        Future<JsonObject> retVal = Future.future();

        String[] values = token.split(" ");
        System.out.println("values[1]: " + values[1]);

        jwtAuth.authenticate(new JsonObject().put("jwt", values[1]), res -> {
            if (res.succeeded()) {
                io.vertx.ext.auth.User theUser = res.result();
                JsonObject principal = theUser.principal();
                retVal.complete(principal);
            } else {
                retVal.fail(res.cause());
            }
        });

        return retVal;
    }

    private void getCurrentUser(RoutingContext routingContext) {

        String headerAuth = routingContext.request().getHeader("Authorization");
        System.out.println("headerAuth: " + headerAuth);

        String[] values = headerAuth.split(" ");
        System.out.println("values[1]: " + values[1]);

        jwtAuth.authenticate(new JsonObject()
                .put("jwt", values[1]), res -> {
            if (res.succeeded()) {
                io.vertx.ext.auth.User theUser = res.result();
                JsonObject principal = theUser.principal();
                System.out.println("theUser: " + theUser.principal().encodePrettily());

                JsonObject message2 = new JsonObject()
                        .put(MESSAGE_ACTION, MESSAGE_ACTION_LOOKUP_USER_BY_EMAIL)
                        .put(MESSAGE_LOOKUP_CRITERIA, principal.getString("email"));

                vertx.eventBus().send(MESSAGE_ADDRESS, message2, ar -> {
                    if (ar.succeeded()) {
                        System.out.println(MESSAGE_ACTION_LOOKUP_USER_BY_EMAIL + "succeeded");
                        JsonObject userJson = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                        final User returnedUser = new User(userJson);
                        // get the JWT Token
                        returnedUser.setToken(jwtAuth.generateToken(principal, new JWTOptions().setIgnoreExpiration(true)));
                        routingContext.response()
                                .setStatusCode(200)
                                .putHeader("Content-Type", "application/json; charset=utf-8")
                                //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                                .end(Json.encodePrettily(returnedUser.toConduitJson()));
                    } else {
                        System.out.println("Did Not Find User");
                        routingContext.response().setStatusCode(422)
                                .putHeader("content-type", "application/json; charset=utf-8")
                                //.putHeader("Content-Length", String.valueOf(loginError.toString().length()))
                                .end(Json.encodePrettily(new AuthenticationError(ErrorMessages.AUTHENTICATION_ERROR_DEFAULT + " " + ar.cause().getMessage())));
                    }
                });

            } else {
                //failed!
                System.out.println("authentication failed ");
            }

        });
    }

    private void registerUser(RoutingContext routingContext) {

        // marshall our payload into a User object
        //final User user = Json.decodeValue(routingContext.getBodyAsString(), User.class);

        final User user = Json.decodeValue(routingContext.getBodyAsJson().getJsonObject("user").toString(), User.class);

        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, MESSAGE_ACTION_REGISTER)
                .put(MESSAGE_VALUE_USER, routingContext.getBodyAsJson().getJsonObject("user"));

        System.out.println(message.getJsonObject("user"));
        vertx.eventBus().send(MESSAGE_ADDRESS, message, ar -> {
            if (ar.succeeded()) {
                JsonObject userJson = ((JsonObject) ar.result().body()).getJsonObject(MESSAGE_RESPONSE_DETAILS);
                final User returnedUser = new User(userJson);
                // get the JWT Token
                returnedUser.setToken(jwtAuth.generateToken(new JsonObject().put("email", user.getEmail()).put("password", user.getPassword()), new JWTOptions().setIgnoreExpiration(true)));
                routingContext.response()
                        .setStatusCode(201)
                        .putHeader("Content-Type", "application/json; charset=utf-8")
                        //.putHeader("Content-Length", String.valueOf(userResult.toString().length()))
                        .end(Json.encodePrettily(returnedUser.toConduitJson()));
            } else {
                routingContext.response()
                        .setStatusCode(422)
                        .putHeader("content-type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(new RegistrationError(ar.cause().getMessage())));
            }
        });
        // insert into the authentication collection

/*
    loginAuthProvider.insertUser(user.getEmail(), user.getPassword(), null, null, res -> {

      if (res.succeeded()) {
        // now save to the conduit_users collection
        user.set_id(res.result());
        mongoClient.insert(MongoConstants.COLLECTION_NAME_USERS, user.toMongoJson(), r -> {
          if (r.succeeded()) {
            routingContext.response()
              .setStatusCode(201)
              .putHeader("content-type", "application/json; charset=utf-8")
              .end(Json.encodePrettily(user));
          } else {
            routingContext.response()
              .setStatusCode(422)
              .putHeader("content-type", "application/json; charset=utf-8")
              .end(Json.encodePrettily(new RegistrationError(r.cause().getMessage())));
          }
        });
      } else {
        routingContext.response()
          .setStatusCode(422)
          .putHeader("content-type", "application/json; charset=utf-8")
          .end(Json.encodePrettily(new RegistrationError(res.cause().getMessage())));
      }
    });
*/
    }

    private void loginUser(RoutingContext routingContext) {

        final User user = Json.decodeValue(routingContext.getBodyAsJson().getJsonObject("user").toString(), User.class);

        JsonObject authInfo = new JsonObject().put("email", user.getEmail()).put("password", user.getPassword());
        JsonObject message = new JsonObject()
                .put(MESSAGE_ACTION, MESSAGE_ACTION_LOGIN)
                .put(MESSAGE_VALUE_USER, authInfo);

        vertx.eventBus().send(MESSAGE_ADDRESS, message, ar -> {
            if (ar.succeeded()) {

                JsonObject body = (JsonObject) ar.result().body();
                final User returnedUser = new User(body.getJsonObject(MESSAGE_RESPONSE_DETAILS));
                returnedUser.setToken(jwtAuth.generateToken(authInfo, new JWTOptions().setIgnoreExpiration(true)));
                routingContext.response()
                        .setStatusCode(200)
                        .putHeader("Content-Type", "application/json; charset=utf-8")
                        .end(Json.encodePrettily(returnedUser.toConduitJson()));
            } else {
                System.out.println("Did Not Find User");
                routingContext.response().setStatusCode(422)
                        .putHeader("content-type", "application/json; charset=utf-8")
                        //.putHeader("Content-Length", String.valueOf(loginError.toString().length()))
                        .end(Json.encodePrettily(new AuthenticationError(ErrorMessages.AUTHENTICATION_ERROR_DEFAULT + " " + ar.cause().getMessage())));
            }
        });

    }


}

