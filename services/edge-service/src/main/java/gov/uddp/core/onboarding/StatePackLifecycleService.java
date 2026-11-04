package gov.uddp.core.onboarding;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.StatePackLoader;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Minimal auditable lifecycle for the immutable pack/version selected by an onboarding run. */
@Service
public class StatePackLifecycleService {
    private final PostgresConnectionFactory connections;
    private final StatePackLoader packs;
    public StatePackLifecycleService(PostgresConnectionFactory connections, StatePackLoader packs) { this.connections = connections; this.packs = packs; }

    public UUID register(String packId) {
        var pack = packs.find(packId).orElseThrow(() -> new OnboardingNotFoundException("Unknown State Pack: " + packId));
        try (var c = connections.open(); var s = c.prepareStatement("insert into state_pack_lifecycle(pack_id,state_code,version,status) values(?,?,?,'DRAFT') on conflict(pack_id,version) do update set pack_id=excluded.pack_id returning state_pack_id")) {
            s.setString(1, pack.manifest().packId()); s.setString(2, pack.manifest().stateCode()); s.setString(3, pack.manifest().version()); var r = s.executeQuery(); r.next(); return r.getObject(1, UUID.class);
        } catch (Exception e) { throw new IllegalStateException("Cannot register State Pack", e); }
    }
    public void transition(String packId, String version, String target) {
        String next = target.toUpperCase(Locale.ROOT);
        if (!next.matches("VALIDATED|ACTIVE|RETIRED")) throw new IllegalArgumentException("Unsupported lifecycle target " + target);
        try (var c = connections.open(); var select = c.prepareStatement("select status from state_pack_lifecycle where pack_id=? and version=? for update")) {
            c.setAutoCommit(false); select.setString(1, packId); select.setString(2, version); var r=select.executeQuery(); if(!r.next()) throw new OnboardingNotFoundException("State Pack lifecycle not registered: " + packId + " " + version);
            String current=r.getString(1); boolean allowed=(current.equals("DRAFT")&&next.equals("VALIDATED"))||(current.equals("VALIDATED")&&next.equals("ACTIVE"))||(current.equals("ACTIVE")&&next.equals("RETIRED"));
            if(!allowed) throw new OnboardingConflictException("Invalid State Pack lifecycle transition " + current + " -> " + next);
            try(var update=c.prepareStatement("update state_pack_lifecycle set status=?,validated_at=case when ?='VALIDATED' then now() else validated_at end,activated_at=case when ?='ACTIVE' then now() else activated_at end,retired_at=case when ?='RETIRED' then now() else retired_at end where pack_id=? and version=?")){update.setString(1,next);update.setString(2,next);update.setString(3,next);update.setString(4,next);update.setString(5,packId);update.setString(6,version);update.executeUpdate();} c.commit();
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new IllegalStateException("Cannot transition State Pack lifecycle",e); }
    }
    public UUID active(String packId, String version) {
        try(var c=connections.open();var s=c.prepareStatement("select state_pack_id from state_pack_lifecycle where pack_id=? and version=? and status='ACTIVE'")){s.setString(1,packId);s.setString(2,version);var r=s.executeQuery();if(!r.next())throw new OnboardingConflictException("State Pack must be ACTIVE for onboarding: "+packId+" "+version);return r.getObject(1,UUID.class);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot read State Pack lifecycle",e);}
    }
}
