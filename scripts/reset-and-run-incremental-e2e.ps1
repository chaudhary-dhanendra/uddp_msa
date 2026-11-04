<# M11 incremental proof: the 3-state baseline (7 -> 5 Golden), then State 4 onboarded incrementally (9 -> 6 Golden)
   with every baseline Golden ID, including Ram Singh's, unchanged. #>
& "$PSScriptRoot\reset-and-run-e2e.ps1" -IncrementalStatePackDirectory 'm10-state-packs' `
  -ExpectIncrementalRaw 9 -ExpectIncrementalCanonical 9 -ExpectIncrementalGolden 6 -ExpectIncrementalAutoMatch 3 -ExpectIncrementalFamilies 8 @args
exit $LASTEXITCODE
