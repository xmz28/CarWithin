import Foundation

enum ZxMotoSocketError: LocalizedError {
    case protocolFailure(String)
    case timeout(String)
    case closed
    case system(String, Int32)
    case channel(String, Error)

    var errorDescription: String? {
        switch self {
        case .protocolFailure(let text): return "ZxMoto 协议：\(text)"
        case .timeout(let text): return "ZxMoto 等待超时：\(text)"
        case .closed: return "ZxMoto 会话已关闭"
        case .channel(let name, let error): return "\(name)：\(error.localizedDescription)"
        case .system(let text, let code): return "\(text)失败（\(code)）"
        }
    }
}
