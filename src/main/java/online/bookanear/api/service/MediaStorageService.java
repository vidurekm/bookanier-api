package online.bookanear.api.service;

public interface MediaStorageService {
    void put(String key, byte[] bytes, String contentType);
    byte[] get(String key);
    void delete(String key);
}
