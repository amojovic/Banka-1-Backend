package dto

type PageResponse[T any] struct {
	Content       []T `json:"content"`
	Page          int `json:"page"`
	Number        int `json:"number"`
	Size          int `json:"size"`
	TotalElements int `json:"totalElements"`
	TotalPages    int `json:"totalPages"`
}
