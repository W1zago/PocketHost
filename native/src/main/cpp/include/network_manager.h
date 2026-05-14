#ifndef ANSERVER_NETWORK_MANAGER_H
#define ANSERVER_NETWORK_MANAGER_H

#include <string>
#include <vector>
#include <map>

class NetworkManager {
public:
    static bool bindPort(int port, const std::string& protocol);
    static bool unbindPort(int port);
    static bool isPortOpen(int port);
    static std::vector<int> getActivePorts();
    static bool isPortAvailable(int port);

private:
    static std::map<int, int> boundPorts;
};

#endif
