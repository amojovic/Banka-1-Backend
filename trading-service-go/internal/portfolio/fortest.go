package portfolio

// fortest.go exposes a test-only constructor so external test packages (notably
// internal/http handler tests) can build a portfolio.Service over the package's
// unexported collaborator interfaces — every method on them is exported, so an
// outside test can implement them. Production wiring stays on NewService.

// NewServiceForTest assembles a Service from stubbed collaborators.
func NewServiceForTest(repo portfolioRepo, market marketLister, account accountMover, tax TaxReporter, runInTx txRunner) *Service {
	return &Service{repo: repo, market: market, account: account, tax: tax, runInTx: runInTx}
}
