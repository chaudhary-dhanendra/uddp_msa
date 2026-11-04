package gov.uddp.core.api.v1;

import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.read.AssetReadService;
import gov.uddp.core.read.AssetReadService.ResidentAssets;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Assets: a Golden Resident's assets across states. Read-only. */
public final class AssetControllers {
    private AssetControllers() {
    }

    @RestController
    @RequestMapping("/api/v1/golden-residents")
    @Tag(name = "Golden Residents")
    @MaskPii
    public static class ResidentAssetController {
        private final AssetReadService assets;

        public ResidentAssetController(AssetReadService assets) {
            this.assets = assets;
        }

        @Operation(operationId = "getGoldenResidentAssets",
                summary = "A Golden Resident's assets across states, with the benefit each relates to")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}/assets")
        @PreAuthorize("hasAuthority('PERM_BENEFIT_VIEW')")
        public ResidentAssets assets(@PathVariable UUID id) {
            return assets.assetsOf(id);
        }
    }
}
