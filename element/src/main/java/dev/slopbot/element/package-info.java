// Required annotation for an Element. Will recursively search folders
// from this point to include classes in the Element if recursive is true.
@ElementDefinition(recursive = true)
// Enables DI via Guice
@GuiceElementModule(SlopModule.class)
// Allows injecting DAO layer from Elements Core (Transaction, Datastore access, LargeObjectDao…)
@ElementDependency("dev.getelements.elements.sdk.dao")
// Allows injecting Service layer from Elements Core (UserService, session auth…)
@ElementDependency("dev.getelements.elements.sdk.service")
// The platform's Mongo Element: Datastore injection for this element's own entities.
@ElementDependency("dev.getelements.elements.sdk.mongo")
//
// Element-local services the element's own classes resolve through the SDK
// ServiceLocator (Element.getServiceLocator().getInstance(...)). Each of these
// MUST be declared here: the GuiceElementLoader builds the element's injector
// through GuiceSpiModule, which only lifts a key to the injector's top level
// (where GuiceServiceLocator.getExistingBinding resolves it) for keys declared
// via @ElementService/@ElementServiceExport. A key bound only inside the
// SlopModule PrivateModule is invisible to the ServiceLocator — requests fail
// with SdkServiceNotFoundException (seen live on 3.9.0-rc-9: GET /slop/api/slop
// → 500 via SlopResource's lazy `engine` delegate).
//
// Every type below is also expose()d by SlopModule, which is what puts the
// binding into GuiceSpiModule's namespace for the upward expose; the pair
// (declaration here + expose() in the module) is required — either alone does
// not produce a top-level binding.
@ElementService(dev.slopbot.element.service.SlopEngine.class)
@ElementService(dev.slopbot.element.engine.SlopEvents.class)
@ElementService(dev.slopbot.element.dao.SlopDao.class)
@ElementService(dev.slopbot.element.dao.SlopAuditDao.class)
@ElementService(dev.slopbot.element.dao.SlopAuditor.class)
@ElementService(dev.slopbot.element.service.SlopAssetAdmin.class)
@ElementService(dev.slopbot.element.rest.SlopAuth.class)
@ElementService(dev.slopbot.element.mcp.McpHandler.class)
@ElementService(dev.slopbot.element.mcp.OidcValidator.class)
@ElementService(dev.slopbot.element.ws.PodWebsocketClient.class)
@ElementService(dev.slopbot.element.ws.PageRelay.class)
//
// Borrowed runtime packages. The element's implementation classloader
// default-denies base-classloader types, so each package the element's own
// code references but does not bundle must be requested explicitly (the same
// convention fake-the-music's package-info follows):
//
// - java.net.http — OidcValidator's companion HttpClient (JDK client for JWKS
//   discovery). Live-proven: without the request, the first MCP POST dies in
//   OidcValidator.<clinit> with NoClassDefFoundError java/net/http/HttpClient.
// - com.fasterxml.jackson.databind — Jackson rides the RUNTIME (/opt/elements/lib)
//   and is deliberately NOT bundled in the elm (a bundled copy would conflict
//   with the runtime's shared ObjectMapper).
@ElementPackageRequest("java.net.http")
@ElementPackageRequest("com.fasterxml.jackson.databind")
// The session surface resolves the adopted cookie directly off the servlet
// request (SlopAuth.currentUser(request)) — request the servlet API.
@ElementPackageRequest("jakarta.servlet")
@ElementPackageRequest("jakarta.servlet.http")
package dev.slopbot.element;

import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.annotation.ElementService;
import dev.getelements.elements.sdk.annotation.ElementPackageRequest;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
import dev.slopbot.element.guice.SlopModule;
