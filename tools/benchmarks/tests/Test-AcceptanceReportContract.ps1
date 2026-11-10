$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../AcceptanceReportContract.ps1')
$checks=0
function Expect-Rejection([scriptblock]$Action){$bad=$false;try{& $Action|Out-Null}catch{$bad=$true};if(!$bad){throw 'Acceptance must reject this evidence'};$script:checks++}
$pass=@{tests=2;failures=0;errors=0;skipped=0}
$skip=@{tests=3;failures=0;errors=0;skipped=1}
$known=[pscustomobject]@{Class='hagrid.lausitz.freight.LmdBaselineEndToEndTest';Method='bootsOnRealData';Reason='Assumption failed: PANDA demand not staged — skipping e2e'}
$accepted=Confirm-HagridAcceptanceTotals -Totals $pass
if(@($accepted.DeferredChecks).Count -ne 0){throw 'Passing scope has no deferred checks'};$checks++
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals $skip -SkippedCases @($known)}
$accepted=Confirm-HagridAcceptanceTotals -Totals $skip -SkippedCases @($known) -AllowUnavailableRealData
if(@($accepted.DeferredChecks).Count -ne 1 -or $accepted.DeferredChecks[0].Method -ne 'bootsOnRealData'){throw 'Precisely identify the deferred case'};$checks++
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals $skip -SkippedCases @() -AllowUnavailableRealData}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals $skip -SkippedCases @([pscustomobject]@{Class='hagrid.core.NewRegressionTest';Method='critical';Reason=$known.Reason}) -AllowUnavailableRealData}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals $skip -SkippedCases @([pscustomobject]@{Class=$known.Class;Method=$known.Method;Reason='python not on PATH'}) -AllowUnavailableRealData}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals @{tests=3;failures=1;errors=0;skipped=1} -SkippedCases @($known) -AllowUnavailableRealData}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals @{tests=3;failures=0;errors=1;skipped=1} -SkippedCases @($known) -AllowUnavailableRealData}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals @{tests=1;failures=0;errors=0;skipped=1} -SkippedCases @($known) -AllowUnavailableRealData}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals @{tests=0;failures=0;errors=0;skipped=0}}
Expect-Rejection {Confirm-HagridAcceptanceTotals -Totals @{tests=2;failures=0;errors=0;skipped=0} -SkippedCases @($known) -AllowUnavailableRealData}
Write-Output ('Acceptance report contract: '+$checks+' checks passed; only an explicit known missing-data gate can be deferred')