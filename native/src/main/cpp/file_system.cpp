#include "file_system.h"
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <fstream>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "FileSystem"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::vector<FileInfo> FileSystem::listDirectory(const std::string& path) {
    std::vector<FileInfo> files;

    DIR* dir = opendir(path.c_str());
    if (!dir) {
        LOGE("Failed to open directory: %s", path.c_str());
        return files;
    }

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        FileInfo info;
        info.name = entry->d_name;
        info.path = path + "/" + entry->d_name;

        struct stat st;
        if (stat(info.path.c_str(), &st) == 0) {
            info.size = st.st_size;
            info.modifiedTime = st.st_mtime;
            info.isDirectory = S_ISDIR(st.st_mode);
            info.isReadable = (st.st_mode & S_IRUSR) != 0;
            info.isWritable = (st.st_mode & S_IWUSR) != 0;
            info.isExecutable = (st.st_mode & S_IXUSR) != 0;
        }

        files.push_back(info);
    }

    closedir(dir);
    return files;
}

bool FileSystem::createDirectory(const std::string& path) {
    if (mkdir(path.c_str(), 0755) == 0) {
        LOGI("Created directory: %s", path.c_str());
        return true;
    }
    LOGE("Failed to create directory: %s", path.c_str());
    return false;
}

bool FileSystem::deleteFile(const std::string& path) {
    if (unlink(path.c_str()) == 0) {
        LOGI("Deleted file: %s", path.c_str());
        return true;
    }
    LOGE("Failed to delete file: %s", path.c_str());
    return false;
}

bool FileSystem::deleteDirectory(const std::string& path) {
    DIR* dir = opendir(path.c_str());
    if (!dir) {
        return false;
    }

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) {
            continue;
        }

        std::string fullPath = path + "/" + entry->d_name;
        struct stat st;
        if (stat(fullPath.c_str(), &st) == 0) {
            if (S_ISDIR(st.st_mode)) {
                deleteDirectory(fullPath);
            } else {
                unlink(fullPath.c_str());
            }
        }
    }

    closedir(dir);
    return rmdir(path.c_str()) == 0;
}

bool FileSystem::copyFile(const std::string& source, const std::string& destination) {
    std::ifstream src(source, std::ios::binary);
    if (!src.is_open()) {
        LOGE("Failed to open source file: %s", source.c_str());
        return false;
    }

    std::ofstream dst(destination, std::ios::binary);
    if (!dst.is_open()) {
        LOGE("Failed to open destination file: %s", destination.c_str());
        return false;
    }

    dst << src.rdbuf();
    LOGI("Copied file from %s to %s", source.c_str(), destination.c_str());
    return true;
}

bool FileSystem::moveFile(const std::string& source, const std::string& destination) {
    if (rename(source.c_str(), destination.c_str()) == 0) {
        LOGI("Moved file from %s to %s", source.c_str(), destination.c_str());
        return true;
    }
    LOGE("Failed to move file from %s to %s", source.c_str(), destination.c_str());
    return false;
}

std::string FileSystem::readFile(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) {
        LOGE("Failed to open file: %s", path.c_str());
        return "";
    }

    std::string content((std::istreambuf_iterator<char>(file)),
                        std::istreambuf_iterator<char>());
    return content;
}

bool FileSystem::writeFile(const std::string& path, const std::string& content) {
    std::ofstream file(path);
    if (!file.is_open()) {
        LOGE("Failed to open file for writing: %s", path.c_str());
        return false;
    }

    file << content;
    LOGI("Wrote to file: %s", path.c_str());
    return true;
}

long FileSystem::getFileSize(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) == 0) {
        return st.st_size;
    }
    return -1;
}

bool FileSystem::fileExists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

bool FileSystem::setPermissions(const std::string& path, int mode) {
    if (chmod(path.c_str(), mode) == 0) {
        LOGI("Set permissions for %s to %o", path.c_str(), mode);
        return true;
    }
    LOGE("Failed to set permissions for %s", path.c_str());
    return false;
}
