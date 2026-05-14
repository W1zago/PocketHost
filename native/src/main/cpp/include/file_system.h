#ifndef ANSERVER_FILE_SYSTEM_H
#define ANSERVER_FILE_SYSTEM_H

#include <string>
#include <vector>

struct FileInfo {
    std::string name;
    std::string path;
    long size;
    long modifiedTime;
    bool isDirectory;
    bool isReadable;
    bool isWritable;
    bool isExecutable;
};

class FileSystem {
public:
    static std::vector<FileInfo> listDirectory(const std::string& path);
    static bool createDirectory(const std::string& path);
    static bool deleteFile(const std::string& path);
    static bool deleteDirectory(const std::string& path);
    static bool copyFile(const std::string& source, const std::string& destination);
    static bool moveFile(const std::string& source, const std::string& destination);
    static std::string readFile(const std::string& path);
    static bool writeFile(const std::string& path, const std::string& content);
    static long getFileSize(const std::string& path);
    static bool fileExists(const std::string& path);
    static bool setPermissions(const std::string& path, int mode);
};

#endif
