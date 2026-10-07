import { useState } from "react";
import type { ChangeEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteShoePhoto, listShoePhotos, uploadShoePhoto } from "../../api/gear";
import { ApiError } from "../../api/types";
import { formatDate } from "../../lib/format";
import { ShoePhotoImage } from "./ShoePhotoImage";

const inputStyle: React.CSSProperties = {
  padding: "7px 10px",
  borderRadius: 8,
  border: "1px solid var(--line)",
  background: "var(--elev)",
  fontSize: 12.5,
  color: "var(--ink)",
};

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

export function ShoePhotoTimeline({ shoeId, currentKm }: { shoeId: string; currentKm: number }) {
  const queryClient = useQueryClient();
  const queryKey = ["shoe-photos", shoeId];
  const { data } = useQuery({ queryKey, queryFn: () => listShoePhotos(shoeId) });
  const photos = data?.data ?? [];

  const [file, setFile] = useState<File | null>(null);
  const [takenOn, setTakenOn] = useState(todayIso());
  const [km, setKm] = useState(String(currentKm));
  const [notes, setNotes] = useState("");
  const [error, setError] = useState<string | null>(null);

  const uploadMutation = useMutation({
    mutationFn: () =>
      uploadShoePhoto(shoeId, { image: file!, taken_on: takenOn, km: km ? Number(km) : undefined, notes: notes.trim() || undefined }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey });
      setFile(null);
      setTakenOn(todayIso());
      setKm(String(currentKm));
      setNotes("");
      setError(null);
    },
    onError: (err) => setError(err instanceof ApiError ? err.message : "Could not upload this photo."),
  });

  const deleteMutation = useMutation({
    mutationFn: (photoId: string) => deleteShoePhoto(photoId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey }),
  });

  function onFileSelected(e: ChangeEvent<HTMLInputElement>) {
    setFile(e.target.files?.[0] ?? null);
    setError(null);
  }

  return (
    <div style={{ marginTop: 12, paddingTop: 12, borderTop: "1px solid var(--line)" }}>
      <div className="mono" style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: "0.06em", color: "var(--ink3)", marginBottom: 10 }}>
        WEAR PHOTOS
      </div>

      {photos.length > 0 && (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(84px, 1fr))", gap: 8, marginBottom: 12 }}>
          {photos.map((photo) => (
            <div key={photo.id} style={{ position: "relative" }}>
              <ShoePhotoImage
                photoId={photo.id}
                alt={`${formatDate(photo.taken_on)} - ${photo.km}km`}
                style={{ width: "100%", aspectRatio: "1", objectFit: "cover", borderRadius: 8, border: "1px solid var(--line)" }}
              />
              <button
                onClick={() => {
                  if (window.confirm("Delete this photo?")) deleteMutation.mutate(photo.id);
                }}
                disabled={deleteMutation.isPending}
                title="Delete photo"
                style={{
                  position: "absolute",
                  top: 3,
                  right: 3,
                  width: 18,
                  height: 18,
                  borderRadius: 5,
                  border: "none",
                  background: "rgba(0,0,0,0.55)",
                  color: "#fff",
                  fontSize: 11,
                  lineHeight: "18px",
                  textAlign: "center",
                  padding: 0,
                  cursor: "pointer",
                }}
              >
                ×
              </button>
              <div className="mono" style={{ fontSize: 9.5, color: "var(--ink3)", marginTop: 3, textAlign: "center" }}>
                {formatDate(photo.taken_on)} · {photo.km}km
              </div>
              {photo.notes && (
                <div style={{ fontSize: 9.5, color: "var(--ink3)", textAlign: "center", marginTop: 1 }}>{photo.notes}</div>
              )}
            </div>
          ))}
        </div>
      )}

      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <input type="file" accept="image/jpeg,image/png,image/heic,image/heif" onChange={onFileSelected} style={{ fontSize: 12, color: "var(--ink2)" }} />
        {file && (
          <>
            <div style={{ display: "flex", gap: 6 }}>
              <input type="date" value={takenOn} onChange={(e) => setTakenOn(e.target.value)} style={{ ...inputStyle, flex: 1 }} />
              <input
                type="number"
                value={km}
                onChange={(e) => setKm(e.target.value)}
                placeholder="km"
                style={{ ...inputStyle, width: 70 }}
              />
            </div>
            <input
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Notes (optional) - e.g. wear on lateral heel"
              style={inputStyle}
            />
            <button
              onClick={() => uploadMutation.mutate()}
              disabled={uploadMutation.isPending}
              style={{
                border: "none",
                borderRadius: 8,
                background: "var(--ember)",
                color: "#fff",
                fontSize: 12.5,
                fontWeight: 700,
                padding: "7px 0",
                cursor: "pointer",
                opacity: uploadMutation.isPending ? 0.6 : 1,
              }}
            >
              {uploadMutation.isPending ? "Uploading…" : "Add photo"}
            </button>
          </>
        )}
        {error && <div style={{ fontSize: 11.5, color: "#e0442e" }}>{error}</div>}
      </div>
    </div>
  );
}
