export const GUIDE_COMPLETED_KEY = "spunto:guide:v1";

export function hasCompletedGuide() {
  try {
    return localStorage.getItem(GUIDE_COMPLETED_KEY) === "complete";
  } catch {
    return false;
  }
}

export function markGuideCompleted() {
  try {
    localStorage.setItem(GUIDE_COMPLETED_KEY, "complete");
  } catch {
    // App also keeps completion in memory when browser storage is unavailable.
  }
}
