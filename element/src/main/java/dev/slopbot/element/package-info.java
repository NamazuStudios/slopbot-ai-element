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
package dev.slopbot.element;

import dev.getelements.elements.sdk.annotation.ElementDefinition;
import dev.getelements.elements.sdk.annotation.ElementDependency;
import dev.getelements.elements.sdk.spi.guice.annotations.GuiceElementModule;
import dev.slopbot.element.guice.SlopModule;
