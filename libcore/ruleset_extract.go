package libcore

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

var geoRuleSetLocks sync.Map

// extractLocalGeoRuleSets materializes "geosite:<code>" / "geoip:<code>" local
// rule-set references from the consolidated geo asset databases (geosite.db /
// geoip.db) into binary rule-set files in the working directory.
//
// The pre-1.15 core resolved these references through nekoutils hooks that read
// the databases directly; sing-box 1.15 removed that mechanism, so the
// referenced files must exist on disk before the router parses rule-sets.
func extractLocalGeoRuleSets(options option.Options) error {
	if options.Route == nil {
		return nil
	}
	for _, ruleSet := range options.Route.RuleSet {
		if ruleSet.Type != C.RuleSetTypeLocal || ruleSet.Format != C.RuleSetFormatBinary {
			continue
		}
		path := ruleSet.LocalOptions.Path
		var dbName, code string
		switch {
		case strings.HasPrefix(path, "geosite:"):
			dbName, code = geositeDat, strings.TrimPrefix(path, "geosite:")
		case strings.HasPrefix(path, "geoip:"):
			dbName, code = geoipDat, strings.TrimPrefix(path, "geoip:")
		default:
			continue
		}
		if code == "" {
			return fmt.Errorf("empty geo rule-set code in path %q", path)
		}
		if err := extractGeoRuleSet(dbName, code, path); err != nil {
			return fmt.Errorf("extract rule-set %s: %w", path, err)
		}
	}
	return nil
}

func extractGeoRuleSet(dbName, code, outputPath string) error {
	dbPath := filepath.Join(externalAssetsPath, dbName)
	lockKey, err := filepath.Abs(outputPath)
	if err != nil {
		lockKey = filepath.Clean(outputPath)
	}
	lock, _ := geoRuleSetLocks.LoadOrStore(lockKey, new(sync.Mutex))
	lock.(*sync.Mutex).Lock()
	defer lock.(*sync.Mutex).Unlock()

	if isUsableGeoRuleSetCache(dbPath, outputPath) {
		return nil
	}

	var rules []option.HeadlessRule
	err = nil
	switch dbName {
	case geositeDat:
		g := new(geosite)
		if err = g.Open(dbPath); err == nil {
			rules, err = g.Rules(code)
			// geosite.Reader has no Close; the underlying file is the upstream.
			if closer, ok := g.geositeReader.Upstream().(io.Closer); ok {
				closer.Close()
			}
		}
	case geoipDat:
		g := new(geoip)
		if err = g.Open(dbPath); err == nil {
			rules, err = g.Rules(code)
			g.geoipReader.Close()
		}
	default:
		err = fmt.Errorf("unknown geo database: %s", dbName)
	}
	if err != nil {
		return fmt.Errorf("read %s code %q (update route assets): %w", dbName, code, err)
	}

	tmpFile, err := os.CreateTemp(filepath.Dir(outputPath), "."+filepath.Base(outputPath)+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmpFile.Name()
	defer os.Remove(tmpPath)
	file := tmpFile
	err = srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err = replaceGeoRuleSetFile(tmpPath, outputPath); err != nil {
		return err
	}
	if err = writeGeoRuleSetCacheIdentity(dbPath, outputPath); err != nil {
		return err
	}
	return nil
}

func isUsableGeoRuleSetCache(dbPath, outputPath string) bool {
	outInfo, err := os.Stat(outputPath)
	if err != nil || outInfo.Size() == 0 {
		return false
	}
	dbInfo, err := os.Stat(dbPath)
	if err != nil {
		return false
	}
	identity, err := os.ReadFile(geoRuleSetCacheIdentityPath(outputPath))
	if err != nil || string(identity) != geoRuleSetCacheIdentity(dbInfo) {
		return false
	}
	file, err := os.Open(outputPath)
	if err != nil {
		return false
	}
	defer file.Close()
	_, err = srs.Read(file, false)
	return err == nil
}

func geoRuleSetCacheIdentityPath(outputPath string) string {
	return outputPath + ".nb4a-meta"
}

func geoRuleSetCacheIdentity(dbInfo os.FileInfo) string {
	return fmt.Sprintf("version=%d\nsize=%d\nmtime=%d\n", C.RuleSetVersionCurrent, dbInfo.Size(), dbInfo.ModTime().UnixNano())
}

func writeGeoRuleSetCacheIdentity(dbPath, outputPath string) error {
	dbInfo, err := os.Stat(dbPath)
	if err != nil {
		return err
	}
	identityPath := geoRuleSetCacheIdentityPath(outputPath)
	tmpFile, err := os.CreateTemp(filepath.Dir(outputPath), "."+filepath.Base(identityPath)+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmpFile.Name()
	defer os.Remove(tmpPath)
	if _, err = tmpFile.WriteString(geoRuleSetCacheIdentity(dbInfo)); err == nil {
		err = tmpFile.Close()
	} else {
		tmpFile.Close()
	}
	if err != nil {
		return err
	}
	return replaceGeoRuleSetFile(tmpPath, identityPath)
}

func replaceGeoRuleSetFile(tmpPath, outputPath string) error {
	if err := os.Rename(tmpPath, outputPath); err == nil {
		return nil
	} else if runtime.GOOS != "windows" {
		return err
	}
	// Windows cannot replace an existing path with Rename. Android uses the
	// atomic POSIX branch above; this fallback keeps host-side tests portable.
	if err := os.Remove(outputPath); err != nil && !os.IsNotExist(err) {
		return err
	}
	return os.Rename(tmpPath, outputPath)
}
