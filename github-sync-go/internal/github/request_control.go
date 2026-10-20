package github

import "context"

type acquisitionControlKey struct{}

// WithAcquisitionControl composes platform stopping with the existing quota policy.
func WithAcquisitionControl(ctx context.Context, before func(context.Context) error) context.Context {
	return context.WithValue(ctx, acquisitionControlKey{}, before)
}
func CheckAcquisitionControl(ctx context.Context) error {
	if before, ok := ctx.Value(acquisitionControlKey{}).(func(context.Context) error); ok {
		return before(ctx)
	}
	return ctx.Err()
}
