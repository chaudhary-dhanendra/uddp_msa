package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SchemePackServiceTest {
  @Test void importsYamlCatalogueIdempotentlyWithVersionedExclusivity() throws Exception {
    var db=TestDatabase.freshMigrated(); var service=new SchemePackService(db,new ObjectMapper());
    var catalogue=new SchemeCatalogue(Path.of("../platform/schemes/catalogue.yaml"));
    assertEquals(3,service.importCatalogue(catalogue)); assertEquals(0,service.importCatalogue(catalogue));
    try(var c=db.open();var s=c.prepareStatement("select count(*) from scheme_pack_version where version=1")){var r=s.executeQuery();r.next();assertEquals(3,r.getInt(1));}
    try(var c=db.open();var s=c.prepareStatement("select count(*) from scheme_pack_exclusivity_group where group_code='SOCIAL-PENSION'")){var r=s.executeQuery();r.next();assertEquals(2,r.getInt(1));}
  }
}
