# Contributing to PocketHost

Thank you for your interest in contributing to PocketHost! This document provides guidelines and instructions for contributing.

## Code of Conduct

### Our Pledge
We are committed to providing a welcoming and inclusive environment for all contributors.

### Our Standards
- Be respectful and considerate
- Accept constructive criticism gracefully
- Focus on what's best for the community
- Show empathy towards others

## How to Contribute

### Reporting Bugs

Before creating a bug report:
1. Check existing issues to avoid duplicates
2. Verify the bug exists in the latest version
3. Collect relevant information

Bug report should include:
- Clear, descriptive title
- Steps to reproduce
- Expected behavior
- Actual behavior
- Android version and device model
- App version
- Logs and screenshots if applicable

### Suggesting Features

Feature requests should include:
- Clear description of the feature
- Use cases and benefits
- Possible implementation approach
- Any relevant examples

### Pull Requests

1. **Fork the repository**
   ```bash
   git clone https://github.com/yourusername/PocketHost.git
   ```

2. **Create a feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make your changes**
   - Follow coding standards
   - Write clear commit messages
   - Add tests if applicable
   - Update documentation

4. **Test your changes**
   - Build the project
   - Run existing tests
   - Test on real devices
   - Check for regressions

5. **Commit your changes**
   ```bash
   git commit -m "Add feature: description"
   ```

6. **Push to your fork**
   ```bash
   git push origin feature/your-feature-name
   ```

7. **Create Pull Request**
   - Provide clear description
   - Reference related issues
   - Include screenshots if UI changes

## Development Setup

### Prerequisites
- Android Studio Arctic Fox or newer
- JDK 17
- Android SDK (API 26-34)
- Android NDK 25.0+
- CMake 3.22.1+

### Building
```bash
./gradlew assembleDebug
```

### Running Tests
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Coding Standards

### Kotlin Style Guide
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable names
- Keep functions small and focused
- Add KDoc comments for public APIs

### Example
```kotlin
/**
 * Creates a new server instance.
 *
 * @param name The server name
 * @param type The server type
 * @return The created server or null if failed
 */
suspend fun createServer(name: String, type: ServerType): Server? {
    // Implementation
}
```

### C++ Style Guide
- Follow Google C++ Style Guide
- Use RAII principles
- Proper error handling
- Memory safety

### XML Style Guide
- Use meaningful IDs
- Follow Material Design guidelines
- Proper indentation
- Group related attributes

## Project Structure

```
PocketHost/
в”њв”Ђв”Ђ app/              # Main application
в”њв”Ђв”Ђ core/             # Core business logic
в”њв”Ђв”Ђ native/           # Native C++ code
в””в”Ђв”Ђ plugins/          # Plugin modules
    в””в”Ђв”Ђ minecraft/    # Minecraft plugin
```

## Commit Message Guidelines

### Format
```
type(scope): subject

body

footer
```

### Types
- **feat**: New feature
- **fix**: Bug fix
- **docs**: Documentation changes
- **style**: Code style changes
- **refactor**: Code refactoring
- **test**: Test additions/changes
- **chore**: Build/tooling changes

### Examples
```
feat(server): add auto-restart functionality

Implement automatic server restart on crash with
configurable retry attempts and delay.

Closes #123
```

```
fix(ui): correct server status color

Status indicator was showing wrong color for
STARTING state.

Fixes #456
```

## Testing Guidelines

### Unit Tests
- Test business logic
- Mock dependencies
- Cover edge cases
- Aim for >80% coverage

### Integration Tests
- Test component interactions
- Use real dependencies when possible
- Test critical paths

### UI Tests
- Test user workflows
- Verify UI state changes
- Test error scenarios

## Documentation

### Code Documentation
- Public APIs must have KDoc/Javadoc
- Complex logic needs inline comments
- Update README for major changes

### User Documentation
- Update QUICKSTART.md for new features
- Add examples and screenshots
- Keep documentation in sync with code

## Review Process

### What We Look For
- Code quality and style
- Test coverage
- Documentation
- Performance impact
- Security considerations
- Backwards compatibility

### Review Timeline
- Initial review within 3 days
- Follow-up reviews within 1 day
- Merge after approval from maintainers

## Release Process

### Version Numbering
- MAJOR.MINOR.PATCH (Semantic Versioning)
- Update version in build.gradle.kts
- Update CHANGELOG.md

### Release Checklist
- [ ] All tests passing
- [ ] Documentation updated
- [ ] CHANGELOG.md updated
- [ ] Version bumped
- [ ] Tag created
- [ ] Release notes written

## Getting Help

### Resources
- Documentation in `/docs`
- Code examples in `/examples`
- Issue tracker for questions

### Contact
- GitHub Issues for bugs/features
- Discussions for questions
- Email for security issues

## Recognition

Contributors will be:
- Listed in CONTRIBUTORS.md
- Mentioned in release notes
- Credited in documentation

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

---

Thank you for contributing to PocketHost! рџљЂ
