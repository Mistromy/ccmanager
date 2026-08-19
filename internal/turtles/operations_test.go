package turtles

import "testing"

func TestRegister(t *testing.T) {
	type args struct {
		id   uint16
		name string
	}
	tests := []struct {
		name    string
		args    args
		wantErr bool
	}{
		{name: "success", args: args{id: 66, name: "randomTurtle"}, wantErr: false},
		{name: "turtle already exists", args: args{id: 66, name: "randomTurtle"}, wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := Register(tt.args.id, tt.args.name); (err != nil) != tt.wantErr {
				t.Errorf("Register() error = %v, wantErr %v", err, tt.wantErr)
			}
		})
	}
}
