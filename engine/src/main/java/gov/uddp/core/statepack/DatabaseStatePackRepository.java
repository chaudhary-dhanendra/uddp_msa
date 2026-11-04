package gov.uddp.core.statepack;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** PostgreSQL source of truth for Studio-authored, versioned State Pack documents. */
@Repository
public class DatabaseStatePackRepository {
    private final PostgresConnectionFactory connections; private final ObjectMapper json;
    public DatabaseStatePackRepository(PostgresConnectionFactory connections, ObjectMapper json) { this.connections=connections; this.json=json; }
    public void save(String displayName, LoadedStatePack pack) {
        try (var c=connections.open(); var s=c.prepareStatement("insert into state_pack_document(pack_id,version,state_code,display_name,configuration) values(?,?,?,?,?::jsonb) on conflict(pack_id,version) do nothing")) {
            s.setString(1,pack.manifest().packId()); s.setString(2,pack.manifest().version()); s.setString(3,pack.manifest().stateCode()); s.setString(4,displayName); s.setString(5,json.writeValueAsString(pack)); s.executeUpdate();
        } catch(Exception e) { throw new IllegalStateException("Cannot persist State Pack document",e); }
    }
    /** One current document per pack; historical versions remain immutable in the table. */
    public Collection<LoadedStatePack> all() { return read("select configuration::text from (select distinct on (pack_id) configuration, pack_id, created_at from state_pack_document order by pack_id, created_at desc) current order by pack_id", null); }
    public Optional<LoadedStatePack> find(String key) { var r=read("select configuration::text from state_pack_document where lower(pack_id)=lower(?) or lower(state_code)=lower(?) order by created_at desc limit 1",key); return r.stream().findFirst(); }
    private List<LoadedStatePack> read(String sql,String key) { try(var c=connections.open();var s=c.prepareStatement(sql)){if(key!=null){s.setString(1,key);s.setString(2,key);}var r=s.executeQuery();var out=new java.util.ArrayList<LoadedStatePack>();while(r.next())out.add(json.readValue(r.getString(1),LoadedStatePack.class));return out;}catch(Exception e){ if (!connections.isConfigured()) return List.of(); throw new IllegalStateException("Cannot load database State Packs",e); } }
}
