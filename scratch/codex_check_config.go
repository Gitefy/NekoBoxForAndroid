package main

import (
    "context"
    "fmt"
    "os"
    "github.com/sagernet/sing-box/include"
    "github.com/sagernet/sing-box/option"
)

func main() {
    content, err := os.ReadFile(os.Args[1])
    if err != nil { panic(err) }
    var options option.Options
    if err = options.UnmarshalJSONContext(include.Context(context.Background()), content); err != nil { panic(err) }
    for _, r := range options.Route.Rules {
        if len(r.DefaultOptions.Geosite) > 0 || len(r.DefaultOptions.GeoIP) > 0 {
            panic("removed geosite/geoip route fields")
        }
    }
    for _, r := range options.DNS.Rules {
        if len(r.DefaultOptions.Geosite) > 0 || len(r.DefaultOptions.GeoIP) > 0 {
            panic("removed geosite/geoip DNS fields")
        }
    }
    fmt.Println("Native options decode PASS; legacy geo fields absent (not a service/device test)")
}
