#ifndef ANSERVER_PROOT_MANAGER_H
#define ANSERVER_PROOT_MANAGER_H

#include <string>
#include <map>

class PRootManager {
public:
    static bool initPRoot(const std::string& rootPath);
    static bool mountDirectory(const std::string& source, const std::string& target);
    static bool unmountDirectory(const std::string& target);
    static bool changeRoot(const std::string& newRoot);
    static bool createIsolatedEnvironment(const std::string& name, const std::string& rootPath);
    static bool destroyIsolatedEnvironment(const std::string& name);

private:
    static std::string currentRoot;
    static std::map<std::string, std::string> mountPoints;
    static std::map<std::string, std::string> environments;
};

#endif
