package gov.uddp.shaping.admin.web;

import gov.uddp.core.security.Audited;
import gov.uddp.shaping.admin.PluginAdminService;
import gov.uddp.shaping.admin.ShapingAdminService;
import gov.uddp.shaping.admin.ShapingChanges;
import gov.uddp.shaping.admin.WordListService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shaping administration: the platform word lists, the built-in operations and the Tier-3 plug-ins. A word list
 * change or a plug-in approval/revocation is proposed, then approved or rejected by a different user with
 * SHAPING_APPROVE.
 */
@RestController
@RequestMapping("/api/shaping")
@Tag(name = "Shaping")
public class ShapingAdminController {
    public record ProposeWordList(List<WordListService.Group> groups) {
    }

    public record TestRequest(String parser, String value, Map<String, Object> config) {
    }

    public record ProposePlugin(String action) {
    }

    public record Decision(String reason) {
    }

    public record Operations(List<PluginAdminService.BuiltIn> builtIn) {
    }

    private final WordListService wordLists;
    private final PluginAdminService plugins;
    private final ShapingAdminService admin;

    public ShapingAdminController(WordListService wordLists, PluginAdminService plugins, ShapingAdminService admin) {
        this.wordLists = wordLists;
        this.plugins = plugins;
        this.admin = admin;
    }

    @GetMapping("/word-lists")
    @PreAuthorize("hasAuthority('PERM_SHAPING_VIEW')")
    public List<WordListService.WordList> wordLists() {
        return wordLists.lists();
    }

    @GetMapping("/word-lists/{id}")
    @PreAuthorize("hasAuthority('PERM_SHAPING_VIEW')")
    public WordListService.WordList wordList(@PathVariable String id) {
        return wordLists.list(id);
    }

    @PostMapping("/word-lists/{id}/proposals")
    @PreAuthorize("hasAuthority('PERM_SHAPING_MANAGE')")
    @Audited(action = "SHAPING_WORD_LIST_PROPOSE", resource = "SHAPING_WORD_LIST", idVariable = "id")
    public ShapingChanges.Change proposeWordList(@PathVariable String id, @RequestBody ProposeWordList request,
                                                 Authentication actor) {
        return wordLists.propose(id, request.groups(), actor.getName());
    }

    @PostMapping("/word-lists/test")
    @PreAuthorize("hasAuthority('PERM_SHAPING_VIEW')")
    @Audited(action = "SHAPING_WORD_LIST_TEST", resource = "SHAPING_WORD_LIST")
    public WordListService.ParserTest testValue(@RequestBody TestRequest request) {
        return wordLists.test(request.parser(), request.value(), request.config());
    }

    @GetMapping("/operations")
    @PreAuthorize("hasAuthority('PERM_SHAPING_VIEW')")
    public Operations operations() {
        return new Operations(plugins.builtIns());
    }

    @GetMapping("/plugins")
    @PreAuthorize("hasAuthority('PERM_SHAPING_VIEW')")
    public List<PluginAdminService.PluginInfo> plugins() {
        return plugins.plugins();
    }

    @PostMapping("/plugins/{sha256}/proposals")
    @PreAuthorize("hasAuthority('PERM_SHAPING_MANAGE')")
    @Audited(action = "SHAPING_PLUGIN_PROPOSE", resource = "SHAPING_PLUGIN", idVariable = "sha256")
    public ShapingChanges.Change proposePlugin(@PathVariable String sha256, @RequestBody ProposePlugin request,
                                               Authentication actor) {
        return plugins.propose(sha256, request.action(), actor.getName());
    }

    @GetMapping("/changes")
    @PreAuthorize("hasAuthority('PERM_SHAPING_VIEW')")
    public List<ShapingChanges.Change> changes(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String kind) {
        return admin.changes(status, kind);
    }

    @PostMapping("/changes/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_SHAPING_APPROVE')")
    @Audited(action = "SHAPING_CHANGE_APPROVE", resource = "SHAPING_CHANGE", idVariable = "id")
    public ShapingChanges.Change approve(@PathVariable UUID id, @RequestBody(required = false) Decision decision,
                                         Authentication actor) {
        return admin.approve(id, actor.getName(), decision == null ? null : decision.reason());
    }

    @PostMapping("/changes/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_SHAPING_APPROVE')")
    @Audited(action = "SHAPING_CHANGE_REJECT", resource = "SHAPING_CHANGE", idVariable = "id")
    public ShapingChanges.Change reject(@PathVariable UUID id, @RequestBody Decision decision, Authentication actor) {
        return admin.reject(id, actor.getName(), decision.reason());
    }
}
