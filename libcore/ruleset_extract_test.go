package libcore

import (
	"bufio"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
	"github.com/sagernet/sing-box/common/srs"
)

// writeTestGeositeDB creates a minimal geosite.db fixture with a single code.
func writeTestGeositeDB(t *testing.T, dir string) {
	t.Helper()
	file, err := os.Create(filepath.Join(dir, geositeDat))
	if err != nil {
		t.Fatal(err)
	}
	writer := bufio.NewWriter(file)
	err = geosites.Write(writer, map[string][]geosites.Item{
		"test-ads": {{Type: geosites.RuleTypeDomainSuffix, Value: "ads.example"}},
	})
	if flushErr := writer.Flush(); err == nil {
		err = flushErr
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		t.Fatal(err)
	}
}

func geoRuleSetConfig() string {
	return `{
  "log": {"level": "info"},
  "outbounds": [{"type": "direct", "tag": "direct"}],
  "route": {
    "final": "direct",
    "rules": [
      {"rule_set": ["geosite:test-ads"], "outbound": "direct"}
    ],
    "rule_set": [
      {"type": "local", "tag": "geosite:test-ads", "format": "binary", "path": "geosite:test-ads"}
    ]
  }
}`
}

// TestExtractGeoRuleSetWritesSRS exercises the database -> binary rule-set
// conversion directly with a platform-safe output name.
func TestExtractGeoRuleSetWritesSRS(t *testing.T) {
	tmp := t.TempDir()
	writeTestGeositeDB(t, tmp)

	oldAssets := externalAssetsPath
	externalAssetsPath = tmp + string(os.PathSeparator)
	defer func() { externalAssetsPath = oldAssets }()
	oldWd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Chdir(tmp); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(oldWd)

	const out = "test-ads.srs"
	if err = extractGeoRuleSet(geositeDat, "test-ads", out); err != nil {
		t.Fatalf("extractGeoRuleSet: %v", err)
	}

	file, err := os.Open(filepath.Join(tmp, out))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	ruleSet, err := srs.Read(file, false)
	if err != nil {
		t.Fatalf("extracted file should be a valid binary rule-set: %v", err)
	}
	plain, err := ruleSet.Upgrade()
	if err != nil {
		t.Fatal(err)
	}
	if len(plain.Rules) != 1 {
		t.Fatalf("expected exactly one rule, got %+v", plain.Rules)
	}
	rule := plain.Rules[0].DefaultOptions
	if (len(rule.DomainSuffix) != 1 || rule.DomainSuffix[0] != "ads.example") && rule.DomainMatcher == nil {
		t.Fatalf("expected domain suffix or compiled matcher, got %+v", rule)
	}

	// Second run reuses the up-to-date extracted file.
	if err = extractGeoRuleSet(geositeDat, "test-ads", out); err != nil {
		t.Fatalf("extractGeoRuleSet (cached): %v", err)
	}
}

func TestExtractGeoRuleSetRebuildsCorruptCache(t *testing.T) {
	tmp := t.TempDir()
	writeTestGeositeDB(t, tmp)

	oldAssets := externalAssetsPath
	externalAssetsPath = tmp + string(os.PathSeparator)
	defer func() { externalAssetsPath = oldAssets }()

	outputPath := filepath.Join(tmp, "test-ads.srs")
	if err := os.WriteFile(outputPath, []byte("not an srs file"), 0o600); err != nil {
		t.Fatal(err)
	}
	dbInfo, err := os.Stat(filepath.Join(tmp, geositeDat))
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Chtimes(outputPath, dbInfo.ModTime(), dbInfo.ModTime()); err != nil {
		t.Fatal(err)
	}

	if err = extractGeoRuleSet(geositeDat, "test-ads", outputPath); err != nil {
		t.Fatalf("extractGeoRuleSet should rebuild corrupt cache: %v", err)
	}

	file, err := os.Open(outputPath)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if _, err = srs.Read(file, false); err != nil {
		t.Fatalf("rebuilt cache should be a valid binary rule-set: %v", err)
	}
}

func TestExtractGeoRuleSetSerializesSameOutput(t *testing.T) {
	tmp := t.TempDir()
	writeTestGeositeDB(t, tmp)

	oldAssets := externalAssetsPath
	externalAssetsPath = tmp + string(os.PathSeparator)
	defer func() { externalAssetsPath = oldAssets }()

	outputPath := filepath.Join(tmp, "test-ads.srs")
	const workers = 8
	errs := make(chan error, workers)
	var waitGroup sync.WaitGroup
	for range workers {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()
			errs <- extractGeoRuleSet(geositeDat, "test-ads", outputPath)
		}()
	}
	waitGroup.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatalf("concurrent extraction failed: %v", err)
		}
	}

	file, err := os.Open(outputPath)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	if _, err = srs.Read(file, false); err != nil {
		t.Fatalf("concurrent extraction should leave a valid binary rule-set: %v", err)
	}
}

// TestGeoRuleSetExtraction proves a config referencing "geosite:<code>" local
// rule-sets starts once libcore materializes the binary rule-set file from
// geosite.db. This replaces the nekoutils hook removed with the 1.15 core.
func TestGeoRuleSetExtraction(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("geosite:<code> rule-set paths contain ':' and cannot be created on Windows")
	}
	tmp := t.TempDir()
	writeTestGeositeDB(t, tmp)

	oldAssets := externalAssetsPath
	externalAssetsPath = tmp + string(os.PathSeparator)
	defer func() { externalAssetsPath = oldAssets }()
	oldWd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Chdir(tmp); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(oldWd)

	b, err := NewSingBoxInstance(geoRuleSetConfig(), nil)
	if err != nil {
		t.Fatalf("config with extracted geosite rule-set should start: %v", err)
	}
	b.Close()

	if _, err = os.Stat(filepath.Join(tmp, "geosite:test-ads")); err != nil {
		t.Fatalf("expected extracted rule-set file: %v", err)
	}
}

// TestGeoRuleSetExtractionMissingDB proves a missing geo database produces a
// clear startup error pointing at route assets instead of a raw open failure.
func TestGeoRuleSetExtractionMissingDB(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("geosite:<code> rule-set paths contain ':' and cannot be created on Windows")
	}
	tmp := t.TempDir()

	oldAssets := externalAssetsPath
	externalAssetsPath = tmp + string(os.PathSeparator)
	defer func() { externalAssetsPath = oldAssets }()
	oldWd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Chdir(tmp); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(oldWd)

	_, err = NewSingBoxInstance(geoRuleSetConfig(), nil)
	if err == nil {
		t.Fatal("config referencing a missing geosite database should fail")
	}
	if !strings.Contains(err.Error(), "extract geo rule-sets") {
		t.Fatalf("error should mention geo rule-set extraction, got: %v", err)
	}
}
