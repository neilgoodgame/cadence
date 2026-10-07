import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import { fetchShoePhotoUrl } from "../../api/gear";

/** Shoe photos are served behind normal Bearer-token auth, so a plain `<img src>` can't load
 * one directly - this fetches the bytes once and owns the resulting object URL for as long as
 * the photo id stays the same, revoking it on change/unmount. */
export function ShoePhotoImage({ photoId, alt, style }: { photoId: string; alt: string; style?: CSSProperties }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    fetchShoePhotoUrl(photoId).then((u) => {
      if (cancelled) {
        URL.revokeObjectURL(u);
        return;
      }
      objectUrl = u;
      setUrl(u);
    });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [photoId]);

  if (!url) {
    return <div style={{ ...style, background: "var(--elev)" }} />;
  }
  return <img src={url} alt={alt} style={style} />;
}
