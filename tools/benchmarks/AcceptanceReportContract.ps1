function Confirm-HagridAcceptanceTotals {
 param([Parameter(Mandatory)][hashtable]$Totals,[object[]]$SkippedCases=@(),[switch]$AllowUnavailableRealData)
 foreach($name in @('tests','failures','errors','skipped')) {
  if(!$Totals.ContainsKey($name) -or [long]$Totals[$name] -lt 0){throw 'Missing or invalid current test totals.'}
 }
 if($Totals.tests -le $Totals.skipped -or $Totals.failures -ne 0 -or $Totals.errors -ne 0){throw 'Acceptance requires executed tests with no failures or errors.'}
 if($Totals.skipped -ne $SkippedCases.Count){throw 'Every skipped case requires individual evidence.'}
 $deferred=@()
 foreach($case in $SkippedCases) {
  if(!$AllowUnavailableRealData -or $SkippedCases.Count -ne 1 -or
     $case.Class -ne 'hagrid.lausitz.freight.LmdBaselineEndToEndTest' -or $case.Method -ne 'bootsOnRealData' -or
     $case.Reason -notmatch 'Assumption failed: (PANDA demand not staged|LMD depot CSV not staged)') {
   throw 'Unexpected skipped case; new regressions and other skipped scopes cannot be waived.'
  }
  $deferred+=@([pscustomobject]@{Class=$case.Class;Method=$case.Method;Reason=$case.Reason;Status='DEFERRED_MISSING_DATA'})
 }
 $scope='Current HAGRID automated test sources; native upstream compatibility and production are separate gates.'
 if($deferred.Count){$scope+=' One precisely recorded real-data case is deferred for missing staged input; all available automated cases executed.'}
 return [pscustomobject]@{DeferredChecks=$deferred;Scope=$scope}
}