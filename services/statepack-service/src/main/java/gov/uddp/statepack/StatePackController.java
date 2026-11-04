package gov.uddp.statepack;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * State Pack lifecycle API. Actors are declared names until platform authentication reaches this service (as in the
 * monolith's stewardship API); the maker-checker rule is enforced on them.
 */
@RestController
@RequestMapping("/api/statepacks")
public class StatePackController {

    private final StatePacks packs;

    public StatePackController(StatePacks packs) {
        this.packs = packs;
    }

    /** Body {@code {"maker": name, "files": {relativePath: text}}}; the manifest is {@code state.yaml}. */
    @PostMapping
    public ResponseEntity<StatePacks.PackVersion> publish(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, String> files = (Map<String, String>) body.getOrDefault("files", Map.of());
        return ResponseEntity.status(HttpStatus.CREATED).body(packs.publish((String) body.get("maker"), files));
    }

    @PostMapping("/{packId}/{version}/approve")
    public StatePacks.PackVersion approve(@PathVariable String packId, @PathVariable String version,
                                          @RequestBody Map<String, String> body) {
        return packs.approve(packId, version, body.get("checker"));
    }

    @PostMapping("/{packId}/{version}/reject")
    public StatePacks.PackVersion reject(@PathVariable String packId, @PathVariable String version,
                                         @RequestBody Map<String, String> body) {
        return packs.reject(packId, version, body.get("checker"), body.get("reason"));
    }

    @PostMapping("/{packId}/{version}/activate")
    public StatePacks.PackVersion activate(@PathVariable String packId, @PathVariable String version,
                                           @RequestBody Map<String, String> body) {
        return packs.activate(packId, version, body.get("actor"));
    }

    @GetMapping("/{packId}/{version}")
    public ResponseEntity<StatePacks.PackVersion> get(@PathVariable String packId, @PathVariable String version) {
        return ResponseEntity.of(packs.get(packId, version));
    }

    @GetMapping("/resolve")
    public ResponseEntity<StatePacks.PackVersion> resolve(@RequestParam String stateCode, @RequestParam String version) {
        return ResponseEntity.of(packs.resolve(stateCode, version));
    }

    @GetMapping("/by-digest/{digest}")
    public ResponseEntity<StatePacks.PackVersion> byDigest(@PathVariable String digest) {
        return ResponseEntity.of(packs.byDigest(digest));
    }

    @GetMapping
    public List<StatePacks.PackVersion> list(@RequestParam(required = false) String stateCode,
                                             @RequestParam(required = false) String status) {
        return packs.list(stateCode, status);
    }

    @ExceptionHandler(StatePacks.LifecycleException.class)
    public ResponseEntity<Map<String, String>> conflict(StatePacks.LifecycleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
