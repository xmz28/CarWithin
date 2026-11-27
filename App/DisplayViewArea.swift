import Foundation

/// Part of the output frame where CarPlay draws its interface, given as margins in output pixels.
/// The rest of the frame stays black. Useful when an instrument shows only part of the stream,
/// e.g. a custom dashboard layout that crops the middle of a wider frame.
struct DisplayViewArea: Equatable {
    var left = 0
    var right = 0
    var top = 0
    var bottom = 0

    static let full = DisplayViewArea()
    /// CarPlay needs at least 320 points on each side (see the Tailing output); keep that much.
    static let minimumSide = 320

    var isFull: Bool { self == .full }

    struct Rect: Equatable {
        let x: Int, y: Int, width: Int, height: Int
    }

    /// The drawable area inside a `width`×`height` frame. Margins are clamped so the area keeps
    /// at least `minimumSide` (or the whole frame when it is smaller) and even dimensions.
    func rect(width: Int, height: Int) -> Rect {
        func fit(_ start: Int, _ end: Int, _ total: Int) -> (Int, Int) {
            let room = max(0, total - min(Self.minimumSide, total))
            let start = min(max(0, start), room) & ~1
            let end = min(max(0, end), room - start)
            return (start, (total - start - end) & ~1)
        }
        let (x, w) = fit(left, right, width)
        let (y, h) = fit(top, bottom, height)
        return Rect(x: x, y: y, width: w, height: h)
    }

    private static func key(_ output: Int) -> String { "carPlayViewArea.\(output)" }

    static func load(output: Int, defaults: UserDefaults = .standard) -> DisplayViewArea {
        guard let values = defaults.array(forKey: key(output)) as? [Int], values.count == 4 else { return .full }
        return DisplayViewArea(left: values[0], right: values[1], top: values[2], bottom: values[3])
    }

    func save(output: Int, defaults: UserDefaults = .standard) {
        if isFull {
            defaults.removeObject(forKey: Self.key(output))
        } else {
            defaults.set([left, right, top, bottom], forKey: Self.key(output))
        }
    }
}

#if canImport(UIKit)
import UIKit

/// Pixel margin with a value label and a stepper, sized for a settings row accessory.
final class MarginStepper: UIControl {
    private let stepper = UIStepper()
    private let label = UILabel()

    var value: Int {
        get { Int(stepper.value) }
        set { stepper.value = Double(newValue); label.text = "\(Int(stepper.value)) px" }
    }

    init(maximum: Int, step: Int = 8) {
        super.init(frame: .zero)
        stepper.minimumValue = 0
        stepper.maximumValue = Double(max(0, maximum))
        stepper.stepValue = Double(step)
        stepper.autorepeat = true
        label.font = .monospacedDigitSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .body).pointSize, weight: .regular)
        label.textColor = .secondaryLabel
        label.textAlignment = .right
        let stack = UIStackView(arrangedSubviews: [label, stepper])
        stack.spacing = 10
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            label.widthAnchor.constraint(equalToConstant: 72),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor), stack.trailingAnchor.constraint(equalTo: trailingAnchor),
            stack.topAnchor.constraint(equalTo: topAnchor), stack.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
        stepper.addAction(UIAction { [unowned self] _ in
            label.text = "\(value) px"
            sendActions(for: .valueChanged)
        }, for: .valueChanged)
        value = 0
        // As a table accessory view the frame is not managed by Auto Layout.
        frame.size = systemLayoutSizeFitting(UIView.layoutFittingCompressedSize)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override var isEnabled: Bool { didSet { stepper.isEnabled = isEnabled } }
}

/// Output frame (black) with the CarPlay area (green), drawn to scale.
final class ViewAreaPreview: UIView {
    private var frameSize = (width: 16, height: 9)
    private var area = DisplayViewArea.Rect(x: 0, y: 0, width: 16, height: 9)

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isAccessibilityElement = true
        heightAnchor.constraint(equalToConstant: 150).isActive = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    func update(width: Int, height: Int, area: DisplayViewArea.Rect) {
        frameSize = (max(1, width), max(1, height))
        self.area = area
        accessibilityLabel = CWL("CarPlay 区域 {1}×{2}，输出画面 {3}×{4}",
                                 [String(area.width), String(area.height), String(width), String(height)])
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        let bounds = self.bounds.insetBy(dx: 16, dy: 10)
        let scale = min(bounds.width / CGFloat(frameSize.width), bounds.height / CGFloat(frameSize.height))
        let outer = CGRect(x: bounds.midX - CGFloat(frameSize.width) * scale / 2,
                           y: bounds.midY - CGFloat(frameSize.height) * scale / 2,
                           width: CGFloat(frameSize.width) * scale, height: CGFloat(frameSize.height) * scale)
        UIColor.black.setFill()
        UIBezierPath(roundedRect: outer, cornerRadius: 4).fill()
        let inner = CGRect(x: outer.minX + CGFloat(area.x) * scale, y: outer.minY + CGFloat(area.y) * scale,
                           width: CGFloat(area.width) * scale, height: CGFloat(area.height) * scale)
        UIColor.systemGreen.withAlphaComponent(0.35).setFill()
        let path = UIBezierPath(rect: inner)
        path.fill()
        UIColor.systemGreen.setStroke()
        path.lineWidth = 1.5
        path.stroke()
        UIColor.separator.setStroke()
        let border = UIBezierPath(roundedRect: outer, cornerRadius: 4)
        border.lineWidth = 1
        border.stroke()
    }
}
#endif
