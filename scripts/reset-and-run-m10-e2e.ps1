<# M10 portability proof: combines the immutable M9 packs with the State 4 configuration fixture. #>
& "$PSScriptRoot\reset-and-run-e2e.ps1" -AdditionalStatePackDirectory 'm10-state-packs' `
  -ExpectRaw 9 -ExpectCanonical 9 -ExpectGolden 6 -ExpectAutoMatch 6 -ExpectFamilies 8 @args
exit $LASTEXITCODE
