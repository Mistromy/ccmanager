package server

import "testing"

func Test_validatePort(t *testing.T) {
	type args struct {
		input string
	}
	tests := []struct {
		name    string
		args    args
		wantErr bool
	}{
		{name: "valid", args: args{input: "6767"}, wantErr: false},
		{name: "maximum", args: args{input: "65535"}, wantErr: false},
		{name: "one over", args: args{input: "65536"}, wantErr: true},
		{name: "zero", args: args{input: "0"}, wantErr: true},
		{name: "negative", args: args{input: "-1234"}, wantErr: true},
		{name: "string", args: args{input: "string"}, wantErr: true},
		{name: "empty", args: args{input: ""}, wantErr: true},
		{name: "float", args: args{input: "5.5"}, wantErr: true},
		{name: "too big", args: args{input: "999999"}, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := validatePort(tt.args.input); (err != nil) != tt.wantErr {
				t.Errorf("validatePort() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}
