package libcore

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

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
	// Reuse the extracted file while it is newer than the database.
	if outInfo, err := os.Stat(outputPath); err == nil {
		if dbInfo, dbErr := os.Stat(dbPath); dbErr == nil && !dbInfo.ModTime().After(outInfo.ModTime()) {
			return nil
		}
	}

	var rules []option.HeadlessRule
	var err error
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

	tmpPath := outputPath + ".tmp"
	file, err := os.Create(tmpPath)
	if err != nil {
		return err
	}
	err = srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		os.Remove(tmpPath)
		return err
	}
	return os.Rename(tmpPath, outputPath)
}
