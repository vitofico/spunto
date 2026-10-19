export const colorRoles = [
    "paper",
    "raised",
    "sunken",
    "ink",
    "ink-2",
    "ink-3",
    "rule",
    "rule-strong",
    "field",
    "accent",
    "accent-strong",
    "accent-soft",
    "on-accent",
    "highlight",
    "danger",
    "warn",
];
export function contrast(first, second) {
    const luminance = (hex) => {
        const channels = [1, 3, 5].map(
            (offset) => parseInt(hex.slice(offset, offset + 2), 16) / 255,
        );
        return channels
            .map((channel) =>
                channel <= 0.04045
                    ? channel / 12.92
                    : ((channel + 0.055) / 1.055) ** 2.4,
            )
            .reduce(
                (sum, channel, index) =>
                    sum + channel * [0.2126, 0.7152, 0.0722][index],
                0,
            );
    };
    const values = [luminance(first), luminance(second)].sort((a, b) => a - b);
    return (values[1] + 0.05) / (values[0] + 0.05);
}
export const contrastPairs = [
    ...["ink", "ink-2", "ink-3", "accent", "accent-strong", "danger"].flatMap(
        (foreground) =>
            ["paper", "sunken", "raised"].map((background) => [
                foreground,
                background,
                4.5,
            ]),
    ),
    ...["ink", "ink-2", "ink-3", "accent", "accent-strong"].map(
        (foreground) => [foreground, "accent-soft", 4.5],
    ),
    ["ink", "highlight", 4.5],
    ["on-accent", "accent", 4.5],
    ["on-accent", "accent-strong", 4.5],
    ...["paper", "raised", "sunken"].map((background) => [
        "field",
        background,
        3,
    ]),
];
export function themeIssues(modes) {
    const issues = [];
    for (const mode of ["light", "dark"]) {
        const values = modes?.[mode];
        if (
            !values ||
            typeof values !== "object" ||
            Array.isArray(values) ||
            colorRoles.some(
                (role) =>
                    typeof values[role] !== "string" ||
                    !/^#[0-9a-f]{6}$/i.test(values[role]),
            )
        ) {
            issues.push(`Missing or invalid colour roles in ${mode}`);
            continue;
        }
        for (const [foreground, background, minimum] of contrastPairs) {
            const ratio = contrast(values[foreground], values[background]);
            if (ratio < minimum)
                issues.push(
                    `${foreground} on ${background} is ${ratio.toFixed(2)}:1 in ${mode} (requires ${minimum}:1)`,
                );
        }
    }
    return issues;
}
