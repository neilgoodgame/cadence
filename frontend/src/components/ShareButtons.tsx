import { useState } from "react";

/** Share / "Public · Make private" controls for anything with an owner-toggled is_public flag
 * (currently races). The link has to be the public, login-free page - the owner's own screen
 * needs a login - so sharing something private first asks to make it public. */
export function ShareButtons({
  noun,
  isPublic,
  publicUrl,
  setPublic,
  pending,
  failed,
}: {
  /** e.g. "race" - used in the confirm prompts. */
  noun: string;
  isPublic: boolean;
  publicUrl: string;
  setPublic: (value: boolean) => Promise<unknown>;
  pending: boolean;
  failed: boolean;
}) {
  const [copied, setCopied] = useState(false);

  async function share() {
    if (!isPublic) {
      const ok = window.confirm(
        `Make this ${noun} public? Anyone with the link will be able to view it (read-only) without logging in.`,
      );
      if (!ok) return;
      try {
        await setPublic(true);
      } catch {
        return;
      }
    }
    try {
      await navigator.clipboard.writeText(publicUrl);
    } catch {
      // Clipboard access can be refused (no focus, or the click's user activation expired
      // while the PATCH above was in flight) - fall back to a manual copy.
      window.prompt(`Copy this link to share the ${noun}:`, publicUrl);
      return;
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  }

  function makePrivate() {
    if (window.confirm(`Make this ${noun} private? Anyone you've shared the link with will lose access.`)) {
      setPublic(false).catch(() => {});
    }
  }

  return (
    <>
      {isPublic && (
        <button
          onClick={makePrivate}
          disabled={pending}
          title={`Anyone with the link can view this ${noun} without logging in - click to make it private`}
          style={{
            fontSize: 13,
            fontWeight: 600,
            padding: "8px 14px",
            borderRadius: 8,
            border: "1px solid var(--ember)",
            background: "none",
            color: "var(--ember)",
            cursor: "pointer",
          }}
        >
          Public · Make private
        </button>
      )}
      <button
        onClick={share}
        disabled={pending}
        title={isPublic ? "Copy the public link" : "Make public and copy a link anyone can view"}
        style={{
          fontSize: 13,
          fontWeight: 600,
          padding: "8px 14px",
          borderRadius: 8,
          border: "none",
          background: "var(--ember)",
          color: "#fff",
          cursor: "pointer",
        }}
      >
        {failed ? "Couldn't share" : copied ? "Link copied" : "Share"}
      </button>
    </>
  );
}
