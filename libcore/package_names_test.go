package libcore

import (
	"reflect"
	"testing"
)

func TestParsePackageNames(t *testing.T) {
	got := parsePackageNames(" com.example.z,com.example.a,,com.example.z ")
	want := []string{"com.example.a", "com.example.z"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parsePackageNames() = %#v, want %#v", got, want)
	}
}

func TestParsePackageNamesUnknown(t *testing.T) {
	if got := parsePackageNames(""); len(got) != 0 {
		t.Fatalf("parsePackageNames(unknown) = %#v, want empty", got)
	}
}
